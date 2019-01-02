package mesosphere.marathon
package core.storage
package zookeeper

import java.util.concurrent.{CompletableFuture, CompletionException}

import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.apple.foundationdb.async.{AsyncIterable, AsyncIterator}
import com.apple.foundationdb._
import com.apple.foundationdb.directory.DirectorySubspace
import com.apple.foundationdb.tuple.Tuple
import mesosphere.marathon
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._

class FoundationDBPersistentStore(directory: DirectorySubspace, database: Database)(implicit ec: ExecutionContext, mat: Materializer) extends PersistenceStore {

  val payloadDir = directory.createOrOpen(database, List("payload").asJava).get()

  val childrenDir = directory.createOrOpen(database, List("children").asJava).get()

  private def generateChildren(key: String) = {
    key
      .split("/")
      .filter(_.nonEmpty)
      .foldLeft(Vector("/") -> "") {
        case ((existingPaths, collected), segment) =>
          val newCollected = collected + "/" + segment
          (existingPaths :+ newCollected) -> newCollected
      }._1.tail
      .map { k =>
        val splitted = k.tail.split("/").toList
        val init = splitted.init
        val last = splitted.last
        Tuple.from("/" + init.mkString("/"), last)
      }
  }

  /**
    * A Flow for saving nodes to the store. It takes a stream of nodes and returns a stream of node keys
    * that were successfully stored.
    */
  override def createFlow: Flow[PersistenceStore.Node, String, NotUsed] = Flow[PersistenceStore.Node]
    .mapAsync(150)(create)

  override def create(node: PersistenceStore.Node): Future[String] = createWithinTx(node, database).toScala.recover {
    case c: CompletionException => throw c.getCause
  }

  private def createWithinTx(node: PersistenceStore.Node, tx: TransactionContext): CompletableFuture[String] = {
    val payloadKey = payloadDir.pack(Tuple.from(node.path))

    val splittedKey = node.path.split("/")
    tx.runAsync { tr =>

      val parentSegments = splittedKey.init
      val parentKey = childrenDir.pack(Tuple.from("/" + parentSegments.init.mkString("/"), parentSegments.last))

      val existense = tr.get(payloadKey).toScala zip tr.getKey(KeySelector.firstGreaterOrEqual(parentKey)).toScala

      val f = existense.map {
        case (null, maybeParent) if java.util.Arrays.equals(maybeParent, parentKey) =>
          tr.set(payloadKey, node.data.toArray)
          val childKey = childrenDir.pack(Tuple.from(splittedKey.init.mkString("/"), splittedKey.last))
          tr.set(childKey, Array.empty)
          node.path

        case (null, _) =>
          tr.set(payloadKey, node.data.toArray)
          val childrenKeys = generateChildren(node.path)

          childrenKeys.foreach { tuple =>
            tr.set(childrenDir.pack(tuple), Array.empty)
          }
          node.path

        case _ =>
          tr.cancel()
          tr.close()
          throw new NodeExistsException(node.path)
      }

      f.toJava.toCompletableFuture
    }
  }

  /**
    * A Flow for reading nodes from the store. It takes a stream of node paths and returns a stream of Try[Node] elements.
    */
  override def readFlow: Flow[String, Try[PersistenceStore.Node], NotUsed] = Flow[String]
    .mapAsync(150)(read)

  override def read(path: String): Future[Try[PersistenceStore.Node]] = {
    val tx = database.createTransaction()
    tx.get(payloadDir.pack(Tuple.from(path))).thenApply[Try[PersistenceStore.Node]] { payload =>

      if (payload == null) {
        tx.close()
        Failure(new NoNodeException(path))
      } else {
        tx.close()
        Success(PersistenceStore.Node(path, ByteString.fromArrayUnsafe(payload)))
      }
    }

  }.toScala

  private def readWithinTx(path: String, txc: ReadTransactionContext): CompletableFuture[Try[PersistenceStore.Node]] = {
    txc.readAsync { tr =>
      tr.get(payloadDir.pack(Tuple.from(path))).toScala.map {
        case null => Failure(new NoNodeException(path))
        case payload => Success(PersistenceStore.Node(path, ByteString.fromArrayUnsafe(payload)))
      }.toJava.toCompletableFuture
    }
  }

  /**
    * A Flow for updating nodes in the store. It takes a stream of nodes and returns a stream of paths to indicate
    * a successful update operation for the returned path.
    */
  override def updateFlow: Flow[PersistenceStore.Node, String, NotUsed] = Flow[PersistenceStore.Node]
    .mapAsync(150)(update)

  override def update(node: PersistenceStore.Node): Future[String] = updateWithinTx(node, database).toScala.recover {
    case c: CompletionException => throw c.getCause
  }

  private def updateWithinTx(node: PersistenceStore.Node, txc: TransactionContext): CompletableFuture[String] = {
    txc.runAsync { tr =>
      val payloadKey = payloadDir.pack(Tuple.from(node.path))
      val exists = tr.get(payloadKey).toScala
      exists.map {
        case null =>
          tr.cancel()
          throw new NoNodeException(node.path)
        case _ =>
          tr.set(payloadKey, node.data.toArray)
          node.path
      }.toJava.toCompletableFuture
    }
  }

  /**
    * A Flow for deleting nodes from the repository. It takes a stream of paths and returns a stream of paths to indicate
    * a successful deletion operation for the returned path.
    */
  override def deleteFlow: Flow[String, String, NotUsed] = Flow[String]
    .mapAsync(150)(delete)

  override def delete(path: String): Future[String] = deleteWithinTx(path, database).toScala.recover {
    case c: CompletionException => throw c.getCause
  }

  private def deleteWithinTx(path: String, txc: TransactionContext): CompletableFuture[String] = txc.runAsync { implicit tr =>
    val splitted = path.split("/")
    tr.clear(payloadDir.pack(Tuple.from(path)))
    tr.clear(childrenDir.range(Tuple.from(path))) // clear all children
    tr.clear(childrenDir.pack(Tuple.from(splitted.init.mkString("/"), splitted.last)))

    CompletableFuture.completedFuture(path)
  }

  /**
    * Returns the list of paths for children nodes for the passed path. Note that returned path is absolute and contains
    * node's path prefix.
    *
    * @return
    */
  override def childrenFlow(absolute: Boolean): Flow[String, Try[Children], NotUsed] = Flow[String].mapAsync(150)(p => children(p, absolute))

  override def children(path: String, absolute: Boolean): Future[Try[Children]] = {

    val splitted = path.split("/").filter(_.nonEmpty)
    val lastSegment = splitted.last

    val range = childrenDir.range(Tuple.from(path))

    implicit val tcx = database

    def childExists: Future[Boolean] = {
      tcx.readAsync { tr =>
        val keytuple = Tuple.from("/" + splitted.init.mkString("/"), lastSegment)
        val key = childrenDir.pack(keytuple)
        tr.getKey(KeySelector.firstGreaterOrEqual(key))
      }
        .toScala
        .map { bytes =>
          val tuple = childrenDir.unpack(bytes)
          (tuple.getString(0) + "/" + tuple.getString(1)).replace("//", "/").contains(path)
        }
    }

    val f = childExists.flatMap {
      case true =>
        asyncIterableToSource(_.getRange(range))
          .map { kv =>
            val tuple = childrenDir.unpack(kv.getKey)
            tuple.getString(0) -> tuple.getString(1)
          }
          .takeWhile {
            case (path, child) =>
              path.split("/").last == lastSegment
          }
          .map {
            case p @ (path, child) if absolute =>
              path + "/" + child
            case p @ (path, child) =>
              child
          }
          .toMat(Sink.seq)(Keep.right)
          .run()
          .map(c => Success(c))
      case _ =>
        throw new NoNodeException(path)
    }

    f
  }.recover {
    case c: CompletionException => throw c.getCause
  }

  /**
    * Checks for the existence of a node with passed path.
    *
    * @return
    */
  override def existsFlow: Flow[String, (String, Boolean), NotUsed] = Flow[String].mapAsync(150)(s => exists(s).map(s -> _))

  override def exists(path: String): Future[Boolean] = existsWithinTx(path, database).toScala

  private def existsWithinTx(path: String, txc: ReadTransactionContext): CompletableFuture[Boolean] = txc.readAsync { tr =>
    tr.get(payloadDir.pack(Tuple.from(path))).toScala.map {
      case null => false
      case _ => true
    }.toJava.toCompletableFuture
  }

  /**
    * Method syncs state with underlying store.
    *
    * @param path node path to sync
    * @return
    */
  override def sync(path: String): Future[Done] = Future.successful(Done)

  /**
    * Method takes a list of transaction [[mesosphere.marathon.core.storage.zookeeper.PersistenceStore.StoreOp]] operations
    * and submits them. An exception is thrown if one of the operations fail.
    */
  override def transaction(operations: marathon.Seq[PersistenceStore.StoreOp]): Future[Done] = database.runAsync { tr =>
    import PersistenceStore._
    Source(operations).mapAsync(1) {
      case CreateOp(node) =>
        createWithinTx(node, tr).toScala.recover {
          case c: CompletionException => throw c.getCause
        }
      case UpdateOp(node) =>
        updateWithinTx(node, tr).toScala.recover {
          case c: CompletionException => throw c.getCause
        }
      case DeleteOp(path) =>
        deleteWithinTx(path, tr).toScala.recover {
          case c: CompletionException => throw c.getCause
        }
      case CheckOp(path) =>
        existsWithinTx(path, tr).toScala
          .map {
            case true => Done
            case false => throw new NoNodeException(path)
          }
          .recover {
            case c: CompletionException => throw c.getCause
          }
    }.runWith(Sink.ignore).toJava.toCompletableFuture
  }.toScala.recover {
    case c: CompletionException => throw c.getCause
  }

  /**
    * Create a node if none exists with the given path. Return the path of the node. This operation is atomic.
    *
    * @param node to create
    * @return
    */
  override def createIfAbsent(node: PersistenceStore.Node): Future[String] = {
    create(node)
      .recoverWith {
        case _: NodeExistsException => Future(node.path) // CreateIfAbsent hasn't created a new node since it already exists
      }
  }

  private def asyncIterableToSource(getRange: ReadTransaction => AsyncIterable[KeyValue])(
    implicit
    db: Database,
    ec: ExecutionContext): Source[KeyValue, NotUsed] = {
    Source.unfoldResourceAsync[KeyValue, (AsyncIterator[KeyValue], Transaction)](
      () => {
        val transaction = database.createTransaction()
        Future.successful(getRange(transaction.snapshot()).iterator() -> transaction)
      },
      {
        case (asyncIterator, tx) =>
          asyncIterator.onHasNext().toScala.map {
            case java.lang.Boolean.TRUE =>
              Some(asyncIterator.next())
            case _ =>
              None
          }
      },
      {
        case (asyncIterator, tx) =>
          tx.cancel()
          tx.close()
          Future.successful(Done)
      }
    )
  }
}
