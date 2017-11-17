/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.api

import akka.actor.ActorRefFactory
import com.gettyimages.spray.swagger.SwaggerHttpService
import com.wordnik.swagger.model.ApiInfo

import scala.reflect.runtime.universe._

class SwaggerService(implicit override val actorRefFactory: ActorRefFactory)
    extends SwaggerHttpService {

  override def apiTypes   = Seq(typeOf[JobServiceApi], typeOf[MiningServiceApi])
  override def apiVersion = "0.2"
  override def baseUrl    = "/" // let swagger-ui determine the host and port
  override def docsPath   = "api-docs"
  override def apiInfo    = Some(new ApiInfo("Api users", "", "", "", "", ""))

}