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

/*
 * Copyright (c) 2015, Broad Institute, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name Broad Institute, Inc. nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE
 */

package eu.hbp.mip.woken.cromwell.util

import java.net.URL

import com.typesafe.config.{ Config, ConfigException, ConfigValue }
import org.slf4j.LoggerFactory
import scala.reflect.{ ClassTag, classTag }
import scala.collection.JavaConversions._
import scala.util.Try
import scalaz._
import Scalaz._

// TODO: delete with WokenConfig
@deprecated
object ConfigUtil {

  private[this] val validationLogger = LoggerFactory.getLogger("ConfigurationValidation")

  class ConfigValidationException(context: String,
                                  validationException: ConfigException.ValidationFailed)
      extends ConfigException.ValidationFailed(validationException.problems()) {
    override def getMessage: String = {
      val problems = validationException.problems().map(_.problem()).mkString(", ")
      s"$context configuration validation failed : $problems"
    }
  }

  implicit class EnhancedConfig(val config: Config) extends AnyVal {

    private def keys = config.entrySet().toSet map { v: java.util.Map.Entry[String, ConfigValue] =>
      v.getKey
    }

    private def getOption[T](key: String, f: String => T): Option[T] =
      Try(f(key)) match {
        case scala.util.Success(value)                      => Option(value)
        case scala.util.Failure(e: ConfigException.Missing) => None
        case scala.util.Failure(e)                          => throw e
      }

    def getBooleanOption(key: String): Option[Boolean] = getOption(key, config.getBoolean)
    def getStringOption(key: String): Option[String]   = getOption(key, config.getString)
    def getConfigOption(key: String): Option[Config]   = getOption(key, config.getConfig)

    /**
      * For keys that are in the configuration but not in the reference keySet, log a warning.
      */
    def warnNotRecognized(keySet: Set[String], context: String): Unit =
      keys.diff(keySet) match {
        case warnings if warnings.nonEmpty =>
          validationLogger.warn(
            s"Unrecognized configuration key(s) for $context: ${warnings.mkString(", ")}"
          )
        case _ =>
      }

    /**
      * Validates that the value for this key is a well formed URL.
      */
    def validateURL(key: String): ValidationNel[String, URL] = key.validateAny { url =>
      new URL(config.getString(url))
    }

    def validateString(key: String): ValidationNel[String, String] =
      try {
        config.getString(key).successNel
      } catch {
        case e: ConfigException.Missing => s"Could not find key: $key".failureNel
      }

    def validateConfig(key: String): ValidationNel[String, Config] =
      try {
        config.getConfig(key).successNel
      } catch {
        case e: ConfigException.Missing   => s"Could not find key: $key".failureNel
        case e: ConfigException.WrongType => s"key $key cannot be parsed to a Config".failureNel
      }

  }

  implicit class EnhancedValidation[I <: AnyRef](val value: I) extends AnyVal {

    /**
      * Validates this value by applying validationFunction to it and returning a Validation:
      * Returns successNel upon success.
      * If an exception is thrown and is a subtype of E, return failureNel with the exception message.
      * @param validationFunction function that should throw an exception if this value is found not to be valid
      * @tparam O return type of validationFunction
      * @tparam E Restricts the subtype of Exception that should be caught during validation
      */
    def validateAny[O, E <: Exception: ClassTag](
        validationFunction: I => O
    ): ValidationNel[String, O] =
      try {
        validationFunction(value).successNel
      } catch {
        case e if classTag[E].runtimeClass.isInstance(e) => e.getMessage.failureNel
      }
  }

}
