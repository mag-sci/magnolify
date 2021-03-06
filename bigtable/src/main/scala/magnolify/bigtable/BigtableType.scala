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
package magnolify.bigtable

import java.nio.ByteBuffer

import com.google.bigtable.v2.{Cell, Column, Family, Mutation, Row}
import com.google.bigtable.v2.Mutation.SetCell
import com.google.protobuf.ByteString
import magnolia._
import magnolify.shared.Converter
import magnolify.shims.JavaConverters._

import scala.annotation.implicitNotFound
import scala.language.experimental.macros

sealed trait BigtableType[T] extends Converter[T, java.util.List[Column], Seq[SetCell.Builder]] {
  def apply(v: Row, columnFamily: String): T =
    from(
      v.getFamiliesList.asScala
        .find(_.getName == columnFamily)
        .map(_.getColumnsList)
        .getOrElse(java.util.Collections.emptyList())
    )
  def apply(v: T, columnFamily: String, timestampMicros: Long = 0L): Seq[Mutation] =
    to(v).map { b =>
      Mutation
        .newBuilder()
        .setSetCell(b.setFamilyName(columnFamily).setTimestampMicros(timestampMicros))
        .build()
    }
}

object BigtableType {
  implicit def apply[T](implicit f: BigtableField[T]): BigtableType[T] = new BigtableType[T] {
    override def from(xs: java.util.List[Column]): T = f.get(xs, null).get
    override def to(v: T): Seq[SetCell.Builder] = f.put(null, v)
  }

  def mutationsToRow(key: ByteString, mutations: Seq[Mutation]): Row = {
    val families = mutations
      .map(_.getSetCell)
      .groupBy(_.getFamilyName)
      .map {
        case (familyName, setCells) =>
          val columns = setCells
            .sortBy(_.getColumnQualifier.toStringUtf8)
            .map { setCell =>
              Column
                .newBuilder()
                .setQualifier(setCell.getColumnQualifier)
                .addCells(
                  Cell
                    .newBuilder()
                    .setValue(setCell.getValue)
                    .setTimestampMicros(setCell.getTimestampMicros)
                )
                .build()
            }
          Family.newBuilder().setName(familyName).addAllColumns(columns.asJava).build()
      }
    Row.newBuilder().setKey(key).addAllFamilies(families.asJava).build()
  }

  def rowToMutations(row: Row): Seq[Mutation] =
    for {
      family <- row.getFamiliesList.asScala.toSeq
      column <- family.getColumnsList.asScala
      cell <- column.getCellsList.asScala
    } yield Mutation
      .newBuilder()
      .setSetCell(
        SetCell
          .newBuilder()
          .setFamilyName(family.getName)
          .setColumnQualifier(column.getQualifier)
          .setTimestampMicros(cell.getTimestampMicros)
          .setValue(cell.getValue)
      )
      .build()
}

sealed trait Result[+T] {
  def get: T = this match {
    case Result.Column(v)  => v
    case Result.Default(v) => v
    case Result.None       => throw new NoSuchElementException
  }
}

private object Result {
  // value from column bytes
  case class Column[T](value: T) extends Result[T]

  // value from case class default
  case class Default[T](value: T) extends Result[T]

  // value not found
  case object None extends Result[Nothing]
}

sealed trait BigtableField[T] extends Serializable {
  def get(xs: java.util.List[Column], k: String): Result[T]
  def put(k: String, v: T): Seq[SetCell.Builder]
}

object BigtableField {
  trait Primitive[T] extends BigtableField[T] {
    def fromByteString(v: ByteString): T
    def toByteString(v: T): ByteString

    private def columnQualifier(k: String): ByteString =
      ByteString.copyFromUtf8(k)

    override def get(xs: java.util.List[Column], k: String): Result[T] = {
      val v = Columns.find(xs, k)
      if (v == null) Result.None else Result.Column(fromByteString(v.getCells(0).getValue))
    }

    override def put(k: String, v: T): Seq[SetCell.Builder] =
      Seq(
        SetCell
          .newBuilder()
          .setColumnQualifier(columnQualifier(k))
          .setValue(toByteString(v))
      )
  }

  //////////////////////////////////////////////////

  type Typeclass[T] = BigtableField[T]

  def combine[T](caseClass: CaseClass[Typeclass, T]): BigtableField[T] = new BigtableField[T] {
    private def key(prefix: String, label: String): String =
      if (prefix == null) label else s"$prefix.$label"

    override def get(xs: java.util.List[Column], k: String): Result[T] = {
      var fallback = true
      val r = caseClass.construct { p =>
        val cq = key(k, p.label)
        (p.typeclass.get(xs, cq), p.default) match {
          case (Result.Column(x), _) =>
            fallback = false
            x
          case (Result.Default(_), Some(x)) => x
          case (Result.Default(x), None)    => x
          case (Result.None, Some(x))       => x
          case _ =>
            throw new IllegalArgumentException(s"Column qualifier not found: $cq")
        }
      }
      // result is default if all fields are default
      if (fallback) Result.Default(r) else Result.Column(r)
    }

    override def put(k: String, v: T): Seq[SetCell.Builder] =
      caseClass.parameters.flatMap(p => p.typeclass.put(key(k, p.label), p.dereference(v)))
  }

  @implicitNotFound("Cannot derive BigtableField for sealed trait")
  private sealed trait Dispatchable[T]
  def dispatch[T: Dispatchable](sealedTrait: SealedTrait[Typeclass, T]): BigtableField[T] = ???

  implicit def gen[T]: BigtableField[T] = macro Magnolia.gen[T]

  def apply[T](implicit f: BigtableField[T]): BigtableField[T] = f

  def from[T]: FromWord[T] = new FromWord

  class FromWord[T] {
    def apply[U](f: T => U)(g: U => T)(implicit btf: Primitive[T]): Primitive[U] =
      new Primitive[U] {
        def fromByteString(v: ByteString): U = f(btf.fromByteString(v))
        def toByteString(v: U): ByteString = btf.toByteString(g(v))
      }
  }

  private def primitive[T](
    capacity: Int
  )(f: ByteBuffer => T)(g: (ByteBuffer, T) => Unit): Primitive[T] = new Primitive[T] {
    override def fromByteString(v: ByteString): T = f(v.asReadOnlyByteBuffer())
    override def toByteString(v: T): ByteString = {
      val bb = ByteBuffer.allocate(capacity)
      g(bb, v)
      ByteString.copyFrom(bb.array())
    }
  }

  implicit val btfByte = primitive[Byte](java.lang.Byte.BYTES)(_.get)(_.put(_))
  implicit val btChar = primitive[Char](java.lang.Character.BYTES)(_.getChar)(_.putChar(_))
  implicit val btfShort = primitive[Short](java.lang.Short.BYTES)(_.getShort)(_.putShort(_))
  implicit val btfInt = primitive[Int](java.lang.Integer.BYTES)(_.getInt)(_.putInt(_))
  implicit val btfLong = primitive[Long](java.lang.Long.BYTES)(_.getLong)(_.putLong(_))
  implicit val btfFloat = primitive[Float](java.lang.Float.BYTES)(_.getFloat)(_.putFloat(_))
  implicit val btfDouble = primitive[Double](java.lang.Double.BYTES)(_.getDouble)(_.putDouble(_))
  implicit val btfBoolean = from[Byte](_ == 1)(if (_) 1 else 0)

  implicit val btfByteString = new Primitive[ByteString] {
    override def fromByteString(v: ByteString): ByteString = v
    override def toByteString(v: ByteString): ByteString = v
  }
  implicit val btfByteArray = from[ByteString](_.toByteArray)(ByteString.copyFrom)
  implicit val btfString = from[ByteString](_.toStringUtf8)(ByteString.copyFromUtf8)

  implicit val btfBigInt =
    from[ByteString](bs => BigInt(bs.toByteArray))(bi => ByteString.copyFrom(bi.toByteArray))
  implicit val btfBigDecimal = from[ByteString] { bs =>
    val bb = bs.asReadOnlyByteBuffer()
    val scale = bb.getInt
    val unscaled = BigInt(bs.substring(java.lang.Integer.BYTES).toByteArray)
    BigDecimal.apply(unscaled, scale)
  } { bd =>
    val scale = bd.bigDecimal.scale()
    val unscaled = bd.bigDecimal.unscaledValue().toByteArray
    val bb = ByteBuffer.allocate(java.lang.Integer.BYTES + unscaled.length)
    ByteString.copyFrom(bb.putInt(scale).put(unscaled).array())
  }

  implicit def btfOption[A](implicit btf: BigtableField[A]): BigtableField[Option[A]] =
    new BigtableField[Option[A]] {
      override def get(xs: java.util.List[Column], k: String): Result[Option[A]] =
        Columns.findNullable(xs, k).map(btf.get(_, k)) match {
          case Some(Result.Column(v))  => Result.Column(Some(v))
          case Some(Result.Default(v)) => Result.Default(Some(v))
          case Some(Result.None)       => Result.Default(None)
          case None                    => Result.Default(None)
        }

      override def put(k: String, v: Option[A]): Seq[SetCell.Builder] =
        v.toSeq.flatMap(btf.put(k, _))
    }
}

private object Columns {
  private def find(
    xs: java.util.List[Column],
    columnQualifier: String,
    matchPrefix: Boolean
  ): (Int, Int, Boolean) = {
    val cq = ByteString.copyFromUtf8(columnQualifier)
    val pre = if (matchPrefix) ByteString.copyFromUtf8(s"$columnQualifier.") else ByteString.EMPTY
    var low = 0
    var high = xs.size()
    var idx = -1
    var isNested = false
    while (idx == -1 && low < high) {
      val mid = (high + low) / 2
      val current = xs.get(mid).getQualifier
      if (matchPrefix && current.startsWith(pre)) {
        idx = mid
        isNested = true
      } else {
        val c = ByteStringComparator.INSTANCE.compare(current, cq)
        if (c < 0) {
          low = mid + 1
        } else if (c == 0) {
          idx = mid
          low = mid + 1
        } else {
          high = mid
        }
      }
    }

    if (isNested) {
      low = idx - 1
      while (low >= 0 && xs.get(low).getQualifier.startsWith(pre)) {
        low -= 1
      }
      high = idx + 1
      while (high < xs.size() && xs.get(high).getQualifier.startsWith(pre)) {
        high += 1
      }
      (low + 1, high, isNested)
    } else {
      (idx, idx, isNested)
    }
  }

  def find(xs: java.util.List[Column], columnQualifier: String): Column = {
    val (idx, _, _) = find(xs, columnQualifier, false)
    if (idx == -1) null else xs.get(idx)
  }

  def findNullable(
    xs: java.util.List[Column],
    columnQualifier: String
  ): Option[java.util.List[Column]] = {
    val (low, high, isNested) = find(xs, columnQualifier, true)
    if (isNested) {
      Some(xs.subList(low, high))
    } else {
      if (low == -1) None else Some(java.util.Collections.singletonList(xs.get(low)))
    }
  }
}
