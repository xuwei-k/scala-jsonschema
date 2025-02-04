package com.github.andyglow.jsonschema

import java.io.{ByteArrayInputStream, InputStream}

import scala.collection._
import com.github.andyglow.json.{ParseJson, Value}
import json.Schema
import json.schema.validation.Instance._

import scala.util.{Failure, Success, Try}

object ParseJsonSchema {
  import Value._
  import Schema._


  implicit class OptionOps[T](private val x: Option[T]) extends AnyVal {

    def toSuccess(message: String): Try[T] = x.fold[Try[T]](Failure(new Exception(message)))(Success(_))
  }

  implicit class MapOps(private val x: Map[String, Value]) extends AnyVal {

    def str(k: String): Option[String] = x.get(k) collect { case str(x) => x }

    def int(k: String): Option[Int] = x.get(k) collect { case num(x) => x.intValue }

    def obj(k: String): Option[obj] = x.get(k) collect { case x: obj => x }

    def bool(k: String): Option[Boolean] = x.get(k) collect { case bool(x) => x }

    def arr(k: String): Option[Seq[Value]] = x.get(k) collect { case arr(x) => x }

    def set(k: String): Option[Set[Value]] = x.get(k) collect { case arr(x) => x.toSet }

  }

  def apply(x: String): Try[Schema[_]] = apply(new ByteArrayInputStream(x.getBytes))

  def apply(x: InputStream): Try[Schema[_]] = ParseJson(x) flatMap { apply(_) }

  def apply(x: Value, checkSchema: Boolean = true): Try[Schema[_]] = x match {
    case o @ obj(fields)
      if !checkSchema || fields.get("$$schema").contains(str("http://json-schema.org/draft-04/schema#")) =>
      makeType(o)

    case _ => Failure(new Exception("not a json schema"))
  }

  private[jsonschema] def makeType(x: obj): Try[Schema[_]] = {
    val tpe = x.value.str("type")
    val title = x.value.str("title")

    def makeStrOrEnum = x.value.arr("enum") match {
      case None => makeStr
      case Some(arr) =>
        tpe.map(_.toLowerCase) match {
          case None | Some("string")  => Success { `enum` (`string`, arr.toSet) }
          case Some("integer")        => Success { `enum` (`integer`, arr.toSet) }
          case Some("boolean")        => Success { `enum` (`boolean`, arr.toSet) }
          case Some("number")         => Success { `enum` (`number`[Double], arr.toSet) }
          case Some(x)                => Failure { InvalidEnumType(x) }
        }
    }

    def makeStr = Success {
      val str = `string`[String](x.value.str("format") flatMap parseFormat)
      x.value.str("pattern").foldLeft(str) { case (str, p) => str.withValidation(`pattern` := p).asInstanceOf[`string`[String]]}
    }

    def makeBool = Success {
      `boolean`
    }

    def makeInt = Success {
      `integer`
    }

    def makeNum = Success {
      `number`[Double]
    }

    def makeArr = for {
      elementType <- x.value.obj("items").toSuccess("items is not defined") flatMap makeType
    } yield {
      val unique = x.value.bool("uniqueItems") getOrElse false
      `array`(elementType, unique = unique)
    }

    def makeObj = x.value.obj("patternProperties") match {
      case Some(obj(fields)) if fields.nonEmpty && fields.head._2.isInstanceOf[obj] =>
        val (k, v) = fields.head
        makeType(v.asInstanceOf[obj]) map { x => `dictionary`[Any, Any, scala.collection.immutable.Map](x).withValidation(`patternProperties` := k) }
      case _ =>
        val required = x.value.set("required") map { _ collect { case str(x) => x } } getOrElse Set.empty
        x.value.obj("properties").map { _.value }.toSuccess("properties is not defined") flatMap { props =>
        val fields = props.collect { case (k, v: obj) =>
            `object`.Field(k, makeType(v).get, required.contains(k))
        }.toSet

        Success(new `object`(fields))
      }
    }

    val result = tpe.toSuccess("type is not defined") flatMap {
      case "string"  => makeStrOrEnum
      case "boolean" => makeBool
      case "integer" => makeInt
      case "number"  => makeNum
      case "array"   => makeArr
      case "object"  => makeObj
    }

    title.map(t => result.map(_.withTitle(t))).getOrElse(result)
  }

  private[jsonschema] def parseFormat(x: String): Option[Schema.`string`.Format] = {
    import Schema.`string`.Format._

    PartialFunction.condOpt(x) {
      case "date"       => `date`
      case "date-time"  => `date-time`
      case "time"       => `time`
      case "email"      => `email`
      case "hostname"   => `hostname`
      case "ipv4"       => `ipv4`
      case "ipv6"       => `ipv6`
      case "uri"        => `uri`
    }
  }

  case class InvalidEnumType(value: String) extends Exception(s"invalid enum type: $value")
}