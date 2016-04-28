package org.hammerlab.guacamole.main

import java.io.{BufferedWriter, File, FileWriter}

import org.apache.spark.Logging
import org.bdgenomics.utils.cli.Args4j
import org.hammerlab.guacamole.distributed.LociPartitionUtils
import org.hammerlab.guacamole.loci.SimpleRange
import org.hammerlab.guacamole.logging.LoggingUtils.progress
import org.hammerlab.guacamole.reads.{InputFilters, ReadLoadingConfigArgs}
import org.hammerlab.guacamole.reference.{ContigNotFound, ReferenceArgs, ReferenceBroadcast}
import org.hammerlab.guacamole.{Bases, Common, ReadSet}
import org.kohsuke.args4j.{Argument, Option => Args4jOption}

/**
 * This command is used to generate a "partial fasta" which we use in our tests of variant callers. It should be run
 * locally. It is not intended for any large-scale or production use.
 *
 * A "partial fasta" is a fasta file where the reference names look like "chr1:9242255-9242454/249250621". That gives
 * the contig name, the start and end locus, and the total contig size. The associated sequence in the file gives the
 * reference sequence for just the sites between start and end.
 *
 * This lets us package up a subset of a reference fasta into a file that is small enough to version control and
 * distribute.
 *
 * To run this command, build the main Guacamole package, compile test-classes, and run this class with both the
 * assembly JAR and test-classes on the classpath:
 *
 *   mvn package -DskipTests
 *   mvn test-compile
 *   java \
 *     -cp target/guacamole-with-dependencies-0.0.1-SNAPSHOT.jar:target/scala-2.10.5/test-classes \
 *     org.hammerlab.guacamole.main.GeneratePartialFasta \
 *     -o <output path> \
 *     --reference-fasta <fasta path> \
 *     <bam path> [bam path...]
 */
object GeneratePartialFasta extends Logging {

  protected class Arguments
    extends LociPartitionUtils.Arguments
      with ReadLoadingConfigArgs
      with ReferenceArgs {

    @Args4jOption(name = "--output", metaVar = "OUT", required = true, aliases = Array("-o"),
      usage = "Output path for partial fasta")
    var output: String = ""

    @Args4jOption(name = "--reference-fasta", required = true, usage = "Local path to a reference FASTA file")
    var referenceFastaPath: String = null

    @Argument(required = true, multiValued = true, usage = "Reads to write out overlapping fasta sequence for")
    var bams: Array[String] = Array.empty
  }

  def main(rawArgs: Array[String]): Unit = {
    val args = Args4j[Arguments](rawArgs)
    val sc = Common.createSparkContext(appName = "generate-partial-fasta")

    val reference = ReferenceBroadcast(args.referenceFastaPath, sc)
    val lociBuilder = Common.lociFromArguments(args, default = "none")
    val readSets = args.bams.zipWithIndex.map(fileAndIndex =>
      ReadSet(
        sc,
        fileAndIndex._1,
        InputFilters.empty,
        config = ReadLoadingConfigArgs(args)
      )
    )

    val reads = sc.union(readSets.map(_.mappedReads))
    val contigLengths = readSets.head.contigLengths

    val regions = reads.map(read => (read.referenceContig, read.start, read.end))
    regions.collect.foreach(triple => {
      lociBuilder.put(triple._1, triple._2, triple._3)
    })

    val loci = lociBuilder.result

    val fd = new File(args.output)
    val writer = new BufferedWriter(new FileWriter(fd))

    for {
      contig <- loci.contigs
      SimpleRange(start, end) <- contig.ranges
    } {
      try {
        val sequence = Bases.basesToString(reference.getContig(contig.name).slice(start.toInt, end.toInt))
        writer.write(">%s:%d-%d/%d\n".format(contig.name, start, end, contigLengths(contig.name)))
        writer.write(sequence)
        writer.write("\n")
      } catch {
        case e: ContigNotFound => log.warn("No such contig in reference: %s: %s".format(contig, e.toString))
      }
    }
    writer.close()
    progress(s"Wrote: ${args.output}")
  }
}