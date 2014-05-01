/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bdgenomics.guacamole

import com.google.common.collect.{ TreeRangeSet, ImmutableRangeSet, RangeSet, Range }
import scala.collection.immutable.{ SortedMap, NumericRange }
import scala.collection.JavaConversions
import org.bdgenomics.guacamole.LociSet.emptyRangeSet
import com.esotericsoftware.kryo.{ Serializer, Kryo }
import com.esotericsoftware.kryo.io.{ Input, Output }

/**
 * A collection of genomic regions. Maps reference names (contig names) to a set of loci on that contig.
 *
 * Used, for example, to keep track of what loci to call variants at.
 *
 * Since contiguous genomic intervals are a common case, this is implemented with sets of (start, end) intervals.
 *
 * All intervals are half open: inclusive on start, exclusive on end.
 *
 * @param map Map from contig names to the range set giving the loci under consideration on that contig.
 */
case class LociSet(private val map: LociMap[Unit]) {

  /** The contigs included in this LociSet with a nonempty set of loci. */
  lazy val contigs: Seq[String] = map.contigs

  /** The number of loci in this LociSet. */
  lazy val count: Long = map.count

  /** Given a contig name, returns a [[LociSet.SingleContig]] giving the loci on that contig. */
  def onContig(contig: String): LociSet.SingleContig = LociSet.SingleContig(map.onContig(contig))

  /** Returns the union of this LociSet with another. */
  def union(other: LociSet): LociSet = LociSet(map.union(other.map))

  /** Returns a string representation of this LociSet, in the same format that LociSet.parse expects. */
  override def toString(): String = contigs.map(onContig(_).toString).mkString(",")

  def truncatedString(maxLength: Int = 100): String = {
    // TODO: make this efficient, instead of generating the full string first.
    val full = toString()
    if (full.length > maxLength)
      full.substring(0, maxLength) + " [...]"
    else
      full
  }

  override def equals(other: Any) = other match {
    case that: LociSet => map.equals(that.map)
    case _             => false
  }
  override def hashCode = map.hashCode

}
object LociSet {
  private type JLong = java.lang.Long
  private val emptyRangeSet = ImmutableRangeSet.of[JLong]()

  /** An empty LociSet. */
  val empty = LociSet(LociMap[Unit]())

  /**
   * Given a sequence of (contig name, start locus, end locus) triples, returns a LociSet of the specified
   * loci. The intervals supplied are allowed to overlap.
   */
  def newBuilder(): Builder = new Builder()
  class Builder {
    val wrapped = new LociMap.Builder[Unit]
    def put(contig: String, start: Long, end: Long): Builder = {
      wrapped.put(contig, start, end, Unit)
      this
    }
    def result(): LociSet = LociSet(wrapped.result)
  }

  /** Return a LociSet of a single genomic interval. */
  def apply(contig: String, start: Long, end: Long): LociSet = {
    (new Builder).put(contig, start, end).result
  }

  /**
   * Return a LociSet parsed from a string representation.
   *
   * @param loci A string of the form "CONTIG:START-END,CONTIG:START-END,..." where CONTIG is a string giving the
   *             contig name, and START and END are integers. Spaces are ignored.
   */
  def parse(loci: String): LociSet = {
    val syntax = """^([\pL\pN]+):(\pN+)-(\pN+)""".r
    val sets = loci.replace(" ", "").split(',').map({
      case ""                       => LociSet.empty
      case syntax(name, start, end) => LociSet(name, start.toLong, end.toLong)
      case other                    => throw new IllegalArgumentException("Couldn't parse loci range: %s".format(other))
    })
    union(sets: _*)
  }

  /** Returns union of specified [[LociSet]] instances. */
  def union(lociSets: LociSet*): LociSet = {
    lociSets.reduce(_.union(_))
  }

  /**
   * A set of loci on a single contig.
   * @param contig The contig name
   * @param rangeSet The range set of loci on this contig.
   */
  case class SingleContig(map: LociMap.SingleContig[Unit]) {

    /** Is the given locus contained in this set? */
    def contains(locus: Long): Boolean = map.contains(locus)

    /** Returns a sequence of ranges giving the intervals of this set. */
    def ranges(): Iterable[NumericRange[Long]] = map.ranges

    /** Number of loci in this set. */
    def count(): Long = map.count

    /** Is this set empty? */
    def isEmpty(): Boolean = map.isEmpty

    /** Iterator through loci in this set, sorted. */
    def lociIndividually(): Iterator[Long] = map.lociIndividually()

    /** Returns the union of this set with another. Both must be on the same contig. */
    def union(other: SingleContig): SingleContig = SingleContig(map.union(other.map))

    /** Returns whether a given genomic region overlaps with any loci in this LociSet. */
    def intersects(start: Long, end: Long) = !map.getAll(start, end).isEmpty

    override def toString(): String = {
      ranges.map(range => "%s:%d-%d".format(map.contig, range.start, range.end)).mkString(",")
    }
  }
}

// Serialization
// TODO: use a more efficient serialization format than strings.
class LociSetSerializer extends Serializer[LociSet] {
  def write(kyro: Kryo, output: Output, obj: LociSet) = {
    output.writeString(obj.toString)
  }
  def read(kryo: Kryo, input: Input, klass: Class[LociSet]): LociSet = {
    LociSet.parse(input.readString())
  }
}
class LociSetSingleContigSerializer extends Serializer[LociSet.SingleContig] {
  def write(kyro: Kryo, output: Output, obj: LociSet.SingleContig) = {
    assert(kyro != null)
    assert(output != null)
    assert(obj != null)
    output.writeString(obj.toString)
  }
  def read(kryo: Kryo, input: Input, klass: Class[LociSet.SingleContig]): LociSet.SingleContig = {
    assert(kryo != null)
    assert(input != null)
    assert(klass != null)
    val string = input.readString()
    assert(string != null)
    val set = LociSet.parse(string)
    assert(set != null)
    assert(set.contigs.length == 1)
    set.onContig(set.contigs(0))
  }
}

