/*
 * Copyright 2020 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.spotify.elitzur.validators

import java.lang.{StringBuilder => JStringBuilder}

import com.spotify.elitzur.{DataInvalidException, IllegalValidationException, MetricsReporter}
import magnolia._

import scala.collection.mutable
import scala.language.experimental.macros
import scala.reflect.{ClassTag, _}
import scala.util.Try
import scala.collection.compat._
import scala.collection.compat.immutable.ArraySeq

trait Validator[A] extends Serializable {
  def validateRecord(a: PreValidation[A],
                     path: String = "",
                     outermostClassName: Option[String] = None,
                     config: ValidationRecordConfig = DefaultRecordConfig)
  : PostValidation[A]

  def shouldValidate: Boolean
}

abstract class FieldValidator[A: ClassTag] extends Validator[A] {
  def validate(a: PreValidation[A]): PostValidation[A]

  def validationType: String = classTag[A].runtimeClass.getSimpleName

  override def validateRecord(a: PreValidation[A], path: String,
                              outermostClassName: Option[String], config: ValidationRecordConfig)
  : PostValidation[A] =
    validate(a)

  override def shouldValidate: Boolean = true
}

class IgnoreValidator[T: ClassTag] extends FieldValidator[T] {
  override def validationType: String = "None"

  override def validate(a: PreValidation[T]): PostValidation[T] = IgnoreValidation(a.forceGet)

  override def shouldValidate: Boolean = false
}

private[elitzur] class StatusTypeValidator[A <: BaseValidationType[_]: FieldValidator: ClassTag]
  extends FieldValidator[ValidationStatus[A]] {
  type WrappedType = ValidationStatus[A]

  override def validationType: String = classTag[A].runtimeClass.getSimpleName

  override def validate(a: PreValidation[WrappedType]): PostValidation[WrappedType] =
    PostValidationWrapper(
      implicitly[FieldValidator[A]]
        .validate(a.forceGet.asInstanceOf[PreValidation[A]]))
}

private[elitzur]
class StatusOptionTypeValidator[A <: BaseValidationType[_]: FieldValidator: ClassTag]
  extends FieldValidator[ValidationStatus[Option[A]]] with Serializable {
  type WrappedOption = ValidationStatus[Option[A]]

  override def validationType: String = classTag[A].runtimeClass.getSimpleName

  override def validate(a: PreValidation[WrappedOption]): PostValidation[WrappedOption] = {
    val option = a.forceGet.forceGet
    if (option.isEmpty) {
      PostValidationWrapper(Valid(Option.empty))
    } else {
      //TODO: micro-optimize
      PostValidationWrapper(
        implicitly[FieldValidator[A]]
          .validate(Unvalidated(a.forceGet.forceGet.get))
          .map(Some(_)))
    }
  }
}

private[elitzur] class OptionTypeValidator[A <: BaseValidationType[_]: FieldValidator: ClassTag]
  extends FieldValidator[Option[A]] {

  override def validationType: String = classTag[A].runtimeClass.getSimpleName

  override def validate(a: PreValidation[Option[A]]): PostValidation[Option[A]] = {
    val option = a.forceGet
    if (option.isEmpty) {
      Valid(Option.empty)
    }
    else {
      implicitly[FieldValidator[A]]
        .validate(Unvalidated(option.get))
        .map(Option(_))
        .asInstanceOf[PostValidation[Option[A]]]
    }
  }

  override def shouldValidate: Boolean = true
}

private[elitzur] class WrappedValidator[T: Validator] extends Validator[ValidationStatus[T]] {
  override def validateRecord(a: PreValidation[ValidationStatus[T]],
                              path: String,
                              outermostClassName: Option[String],
                              config: ValidationRecordConfig)
  : PostValidation[ValidationStatus[T]] =
    PostValidationWrapper(
      implicitly[Validator[T]]
        .validateRecord(a.forceGet.asInstanceOf[PreValidation[T]], path, outermostClassName,
          config))

  override def shouldValidate: Boolean = true
}

private[elitzur] class OptionValidator[T: Validator] extends Validator[Option[T]] {
  override def validateRecord(a: PreValidation[Option[T]],
                              path: String,
                              outermostClassName: Option[String],
                              config: ValidationRecordConfig)
  : PostValidation[Option[T]] = {
    val option = a.forceGet
    if (option.isEmpty) {
      Valid(None)
    } else {
      implicitly[Validator[T]]
        .validateRecord(Unvalidated(a.forceGet.get), path, outermostClassName, config)
        .map(Some(_))
        .asInstanceOf[PostValidation[Option[T]]]
    }
  }

  override def shouldValidate: Boolean = true

}

@SuppressWarnings(Array("org.wartremover.warts.Var"))
private[elitzur]
class SeqLikeValidator[T: ClassTag: Validator, C[_]](builderFn: () => mutable.Builder[T, C[T]])
                                                    (implicit toSeq: C[T] => IterableOnce[T])
  extends Validator[C[T]] {
  override def validateRecord(a: PreValidation[C[T]],
                              path: String,
                              outermostClassName: Option[String],
                              config: ValidationRecordConfig): PostValidation[C[T]] = {
    // Use mutable state for perf
    var atLeastOneInvalid = false
    val v = implicitly[Validator[T]]
    val builder = builderFn()
    toSeq(a.forceGet).iterator.foreach(ele => {
      val res = v.validateRecord(Unvalidated(ele), path, outermostClassName, config)
      if (!atLeastOneInvalid && res.isInvalid) {
        atLeastOneInvalid = true
      }
      builder += res.forceGet
    })

    if (atLeastOneInvalid) {
      Invalid(builder.result())
    } else {
      Valid(builder.result())
    }
  }

  override def shouldValidate: Boolean = implicitly[Validator[T]].shouldValidate
  def innerValidationType: String =
    implicitly[Validator[T]].asInstanceOf[FieldValidator[_]].validationType

}

private[elitzur] class BaseFieldValidator[A <: BaseValidationType[_]: ClassTag]
  extends FieldValidator[A] {

  override def validationType: String = classTag[A].runtimeClass.getSimpleName

  override def validate(data: PreValidation[A]): PostValidation[A] = {
    if (data.forceGet.checkValid) Valid(data.forceGet) else Invalid(data.forceGet)
  }

}

private[elitzur] class DynamicValidator[A <: DynamicValidationType[_, _, _]: ClassTag]
 extends FieldValidator[A] {
  override def validationType: String = classTag[A].runtimeClass.getSimpleName

  override def validate(a: PreValidation[A]): PostValidation[A] = {
    if (a.forceGet.arg.isEmpty) {
      throw new IllegalValidationException("Can't call `.validate()` on dynamic types without args")
    } else if (a.forceGet.checkValid) {
      Valid(a.forceGet)
    } else {
      Invalid(a.forceGet)
    }
  }
}


object Validator extends Serializable {
  type Typeclass[T] = Validator[T]

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def combine[T](caseClass: CaseClass[Validator, T])
                (implicit reporter: MetricsReporter, tag: ClassTag[T])
  : Validator[T] = {
    val params = caseClass.parameters
    var i = 0
    var shouldValidate = false
    while (i < params.length) {
      val param = params(i)
      if (param.typeclass.shouldValidate) {
        shouldValidate = true
      }
      i = i + 1
    }
    if (shouldValidate) DerivedValidator(caseClass) else new IgnoreValidator[T]
  }

  def dispatch[T](sealedTrait: SealedTrait[Validator, T]): Validator[T] = new Validator[T] {
    def validateRecord(a: PreValidation[T],
                       path: String = "",
                       outermostClassName: Option[String] = None,
                       config: ValidationRecordConfig = DefaultRecordConfig)
    : PostValidation[T] = {
      sealedTrait.subtypes.flatMap { x =>
        // TODO: Same circular dereference/construction here
        val y =
          Try(x.typeclass.validateRecord(Unvalidated(x.cast(a.forceGet)), path, outermostClassName,
            config)).toOption
        y
      }.headOption.getOrElse(
        throw new Exception(
          s"Couldn't find validator for any subtype of ${sealedTrait.typeName.full}"))
    }

    override def shouldValidate: Boolean = true
  }

  //scalastyle:off method.length cyclomatic.complexity
  /**
   * Core validation loop for both dynamic and derived validators
   * This code is deliberately mutable, uses casts to avoid unboxing, and avoids dereferencing
   * Ignore Validators in order to optimize speed at runtime
   */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def validationLoop[T](validatorAccessors: Array[ValidatorAccessor[Any]],
                     constructor: Seq[Any] => T,
                     outermostClassName: String,
                     path: String = "",
                     config: ValidationRecordConfig = DefaultRecordConfig)
                    (implicit reporter: MetricsReporter): PostValidation[T] = {
    val cs = new Array[Any](validatorAccessors.length)
    var i = 0
    var atLeastOneValid = false
    var atLeastOneInvalid = false

    while (i < validatorAccessors.length) {
      val accessor = validatorAccessors(i)
      val v =
        if (!accessor.validator.isInstanceOf[IgnoreValidator[_]]) {
          val name = new JStringBuilder(path.length + accessor.label.length)
            .append(path).append(accessor.label).toString
          val c = config.fieldConfig(name)
          var o: PostValidation[Any] = null
          var innerValidationType: String = null

          if (accessor.validator.isInstanceOf[FieldValidator[_]]) {
            o = accessor.validator.validateRecord(Unvalidated(accessor.value))
            innerValidationType = accessor.validator.asInstanceOf[FieldValidator[_]].validationType
          } else {
            if (accessor.validator.isInstanceOf[SeqLikeValidator[_, Seq]]) {
              innerValidationType =
                accessor.validator.asInstanceOf[SeqLikeValidator[_, Seq]].innerValidationType
            }

            o = accessor.validator.validateRecord(
              Unvalidated(accessor.value),
              new JStringBuilder(path.length + accessor.label.length + 1)
                .append(path).append(accessor.label).append(".").toString,
              Some(outermostClassName),
              config
            )
          }

          // o and v should be set by now
          // Also do not register a counter if validationType is empty
          if (o.isValid) {
            // NOTE: This will only work for FieldValidator, SeqLikeValidator,
            // and nested case classes too.
            if (c != NoCounter && innerValidationType != null) {
              reporter.reportValid(
                outermostClassName,
                name,
                innerValidationType
              )
            }
            atLeastOneValid = true
          } else if (o.isInvalid) {
            atLeastOneInvalid = true
            if (c == ThrowException) {
              throw new DataInvalidException(
                s"Invalid value ${o.forceGet.toString} found for field $path${accessor.label}")
            }
            if (c != NoCounter && innerValidationType != null) {
              reporter.reportInvalid(
                outermostClassName,
                name,
                innerValidationType
              )
            }
          }

          o.forceGet
        } else {
          accessor.value
        }
      cs.update(i, v)
      i = i + 1
    }
    val record = constructor(ArraySeq.unsafeWrapArray(cs))

    if (atLeastOneInvalid){
      Invalid(record)
    }
    else if (atLeastOneValid) {
      Valid(record)
    }
    else {
      IgnoreValidation(record)
    }
  }
  //scalastyle:on method.length cyclomatic.complexity

  def fallback[T]: Validator[T] = macro ValidatorMacros.issueFallbackWarning[T]

  implicit def gen[T]: Validator[T] = macro ValidatorMacros.wrappedValidator[T]

  private[validators]
  def wrapSeqLikeValidator[T: ClassTag: Validator, C[_]](builderFn: () => mutable.Builder[T, C[T]])
                                                        (implicit toSeq: C[T] => IterableOnce[T],
                                                         ev: ClassTag[C[T]]): Validator[C[T]] = {
    if (implicitly[Validator[T]].shouldValidate) {
      new SeqLikeValidator[T, C](builderFn)
    }
    else {
      new IgnoreValidator[C[T]]
    }
  }
}
