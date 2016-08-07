package org.hammerlab.guacamole.readsets.rdd

import org.apache.spark.rdd.RDD
import org.apache.spark.{Accumulable, SparkContext}
import org.hammerlab.guacamole.loci.partitioning.LociPartitioner.PartitionIndex
import org.hammerlab.guacamole.loci.partitioning.{LociPartitionerArgs, LociPartitioning}
import org.hammerlab.guacamole.loci.set.LociSet
import org.hammerlab.guacamole.logging.LoggingUtils.progress
import org.hammerlab.guacamole.readsets.{NumSamples, PerSample, SampleId}
import org.hammerlab.guacamole.reference.ReferenceRegion
import org.hammerlab.magic.accumulables.{HistogramParam, HashMap => MagicHashMap}
import org.hammerlab.magic.rdd.KeyPartitioner
import org.hammerlab.magic.stats.Stats

import scala.reflect.ClassTag

/**
 * Groups a [[LociPartitioning]] with an [[RDD[ReferenceRegion]]] that has already been partitioned according to that
 * partitioning.
 *
 * This means some regions will occur multiple times in the RDD (due to regions straddling partition boundaries, so it's
 * important not to confuse this with a regular RDD[ReferenceRegion].
 */
class PartitionedRegions[R <: ReferenceRegion: ClassTag](val numSamples: NumSamples,
                                                         @transient keyedRegions: RDD[(SampleId, R)],
                                                         @transient partitioning: LociPartitioning)
  extends Serializable {

  assert(
    keyedRegions.getNumPartitions == lociSetsRDD.getNumPartitions,
    s"reads partitions: ${keyedRegions.getNumPartitions}, loci partitions: ${lociSetsRDD.getNumPartitions}"
  )

  def sc: SparkContext = keyedRegions.sparkContext

  val numLoci = sc.accumulator(0L, "numLoci")

  def mapPartitions[V: ClassTag](f: (Iterator[(SampleId, R)], LociSet) => Iterator[V]): RDD[V] =
    keyedRegions
      .zipPartitions(
        lociSetsRDD,
        preservesPartitioning = true
      )(
        (regionsIter, lociIter) => {
          val loci = lociIter.next()
          if (lociIter.hasNext) {
            throw new Exception(s"Expected 1 LociSet, found ${1 + lociIter.size}.\n$loci")
          }

          numLoci += loci.count

          f(regionsIter, loci)
        }
      )

  private lazy val partitionLociSets: Array[LociSet] =
    partitioning
      .inverse
      .toArray
      .sortBy(_._1)
      .map(_._2)

  lazy val lociSetsRDD: RDD[LociSet] =
    sc
      .parallelize(partitionLociSets, partitionLociSets.length)
      .setName("lociSetsRDD")
}

object PartitionedRegions {

  type IntHist = MagicHashMap[Int, Long]

  def IntHist(): IntHist = MagicHashMap[Int, Long]()

  /**
   * @param regionRDDs RDD of regions to partition.
   * @param loci A [[LociPartitioning]] describing which ranges of genomic loci should be mapped to each Spark
   *             partition.
   * @param args Parameters dictating how `loci` should be partitioned.
   * @tparam R ReferenceRegion type.
   */
  def apply[R <: ReferenceRegion: ClassTag](regionRDDs: PerSample[RDD[R]],
                                            loci: LociSet,
                                            args: LociPartitionerArgs): PartitionedRegions[R] =
    apply(regionRDDs, loci, args, halfWindowSize = 0)

  /**
   * @param regionRDDs RDD of regions to partition.
   * @param loci A [[LociPartitioning]] describing which ranges of genomic loci should be mapped to each Spark
   *             partition.
   * @param halfWindowSize Send a copy of a region to a partition if it passes within this distance of that partition's
   *                       designated loci.
   * @param args Parameters dictating how `loci` should be partitioned.
   * @tparam R ReferenceRegion type.
   */
  def apply[R <: ReferenceRegion: ClassTag](regionRDDs: PerSample[RDD[R]],
                                            loci: LociSet,
                                            args: LociPartitionerArgs,
                                            halfWindowSize: Int): PartitionedRegions[R] = {
    val sc = regionRDDs.head.sparkContext
    val lociPartitioning = LociPartitioning(sc.union(regionRDDs), loci, args, halfWindowSize)

    progress(
      s"Partitioned loci: ${lociPartitioning.numPartitions} partitions.",
      "Partition-size stats:",
      lociPartitioning.partitionSizeStats.toString(),
      "",
      "Contigs-spanned-per-partition stats:",
      lociPartitioning.partitionContigStats.toString()
    )

    apply(
      regionRDDs,
      lociPartitioning,
      halfWindowSize,
      !args.quiet
    )
  }

  def apply[R <: ReferenceRegion: ClassTag](regionRDDs: PerSample[RDD[R]],
                                            lociPartitioning: LociPartitioning,
                                            halfWindowSize: Int,
                                            printStats: Boolean): PartitionedRegions[R] = {

    val sc = regionRDDs(0).sparkContext

    val partitioningBroadcast = sc.broadcast(lociPartitioning)

    val numPartitions = lociPartitioning.numPartitions

    progress(s"Partitioning reads according to loci partitioning:\n$lociPartitioning")

    implicit val accumulableParam = new HistogramParam[Int, Long]

    // Combine the RDDs, with each region keyed by its sample ID.
    val keyedRegions: RDD[(SampleId, R)] =
      sc
      .union(
        for {
          (regionsRDD, sampleId) <- regionRDDs.zipWithIndex
        } yield {
          for {
            region <- regionsRDD
          } yield
            sampleId -> region
        }
      )

    // Histogram of the number of copies made of each region (i.e. when a region straddles loci-partition
    // boundaries.
    val regionCopiesHistogram: Accumulable[IntHist, Int] = sc.accumulable(IntHist(), "copies-per-region")

    // Histogram of the number of regions assigned to each partition.
    val partitionRegionsHistogram: Accumulable[IntHist, Int] = sc.accumulable(IntHist(), "regions-per-partition")

    // Emit a copy of each region for each partition's alloted loci that it overlaps.
    val partitionedRegions: RDD[(SampleId, R)] =
      (for {
        // For each region…
        (sampleId, region) <- keyedRegions

        // Partitions that should receive a copy of this region.
        partitions = partitioningBroadcast.value.getAll(region, halfWindowSize)

        // Update the copies-per-region histogram accumulator.
        _ = (regionCopiesHistogram += partitions.size)

        // For each destination partition…
        partition <- partitions
      } yield {

        // Update the regions-per-partition histogram accumulator.
        partitionRegionsHistogram += partition

        // Key (sample-id, region) pairs by a tuple that will direct it to the correct partition, with secondary fields
        // that will be used by intra-partition sorting.
        (partition, region.contigName, region.start) -> (sampleId, region)
      })
      .repartitionAndSortWithinPartitions(KeyPartitioner(numPartitions))  // Shuffle all region copies.
      .values  // Drop the destination partition / sorting tuple-key; leave only the (sample id, region) pairs.
      .setName("partitioned-regions")

    if (printStats) {
      // Need to force materialization for the accumulator to have data… but that's reasonable because anything
      // downstream is presumably going to reuse this RDD.
      val totalReadCopies = partitionedRegions.count

      val originalReads = keyedRegions.count

      // Sorted array of [number of read copies "K"] -> [number of reads that were copied "K" times].
      val regionCopies: Array[(Int, Long)] = regionCopiesHistogram.value.toArray.sortBy(_._1)

      // Number of distinct reads that were sent to at least one partition.
      val readsPlaced = regionCopies.filter(_._1 > 0).map(_._2).sum

      // Sorted array: [partition index "K"] -> [number of reads assigned to partition "K"].
      val regionsPerPartition: Array[(PartitionIndex, Long)] = partitionRegionsHistogram.value.toArray.sortBy(_._1)

      progress(
        s"Placed $readsPlaced of $originalReads (%.1f%%), %.1fx copies on avg; copies per read histogram:"
          .format(
            100.0 * readsPlaced / originalReads,
            totalReadCopies * 1.0 / readsPlaced
          ),
        Stats.fromHist(regionCopies).toString(),
        "",
        "Reads per partition stats:",
        Stats(regionsPerPartition.map(_._2)).toString()
      )
    }

    new PartitionedRegions(regionRDDs.length, partitionedRegions, lociPartitioning)
  }
}
