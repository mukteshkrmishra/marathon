package mesosphere.marathon
package core.appinfo

import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

trait PodStatusService {

  /**
    * @return the status of the pod at the given path, if such a pod exists
    */
  def selectPodStatus(id: PathId, selector: PodSelector = Selector.all): Future[Option[PodStatus]]

  /**
    * @return the statuses of the pods at the given paths, if the pod exists
    */
  def selectPodStatuses(ids: Set[PathId], selector: PodSelector = Selector.all): Future[Seq[PodStatus]]
}
