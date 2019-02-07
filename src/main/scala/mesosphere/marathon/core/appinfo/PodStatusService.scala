package mesosphere.marathon
package core.appinfo

import akka.stream.Materializer
import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

trait PodStatusService {

  /**
    * @return the status of the pod at the given path, if such a pod exists
    */
  def selectPodStatus(id: PathId, selector: PodSelector = Selector.all): Future[Option[PodStatus]]

  /**
    * @return the status of all pods at the given paths, if such a pods exist
    */
  def selectPodStatuses(ids: Set[PathId], selector: PodSelector = Selector.all)(implicit materializer: Materializer): Future[Seq[PodStatus]]
}
