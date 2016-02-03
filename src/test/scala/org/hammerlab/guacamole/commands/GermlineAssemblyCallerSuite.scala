package org.hammerlab.guacamole.commands

import org.apache.spark.SparkContext
import org.hammerlab.guacamole.commands.GermlineAssemblyCaller.Arguments
import org.hammerlab.guacamole.reads.Read
import org.hammerlab.guacamole.reference.ReferenceBroadcast
import org.hammerlab.guacamole.util.TestUtil
import org.hammerlab.guacamole.variants.{AlleleConversions, CalledAllele}
import org.hammerlab.guacamole.{Bases, Common, LociSet, ReadSet}
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}

class GermlineAssemblyCallerSuite extends FunSuite with Matchers with BeforeAndAfter {

  val args = new Arguments

  val input = "illumina-platinum-na12878/NA12878.10k_variants.plus_chr1_3M-3.1M.bam"
  args.reads = TestUtil.testDataPath(input)
  args.loci = "chr1:772754-772755"
  args.parallelism = 2

  var sc: SparkContext = _
  var reference: ReferenceBroadcast = _
  var readSet: ReadSet = _

  before {
    sc = Common.createSparkContext()
    reference = ReferenceBroadcast(referenceFastaPath, sc)
    readSet = Common.loadReadsFromArguments(
      args,
      sc,
      Read.InputFilters(mapped = true, nonDuplicate = true))
    readSet.mappedReads.persist()
  }

  after {
    sc.stop()
  }

  val referenceFastaPath = TestUtil.testDataPath("illumina-platinum-na12878/chr1.prefix.fa")
  val loci = Common.loci(args)

  def discoverGenotypesAtLoci(loci: String): Seq[CalledAllele] = {
    GermlineAssemblyCaller.Caller.discoverGenotypes(
      readSet.mappedReads,
      kmerSize = 45,
      snvWindowRange = 75,
      minOccurrence = 3,
      reference = reference,
      lociOfInterest = LociSet.parse(loci).result,
      parallelism = args.parallelism
    ).collect().sortBy(_.start)
  }

  test("test assembly caller: illumina platinum tests; homozygous snp") {

    val loci = "chr1:772754-772755"
    val variants = discoverGenotypesAtLoci(loci)

    variants.length should be(1)
    val variant = variants(0)
    variant.referenceContig should be("chr1")
    variant.start should be(772754)
    Bases.basesToString(variant.allele.refBases) should be("A")
    Bases.basesToString(variant.allele.altBases) should be("C")

  }

  test("test assembly caller: illumina platinum tests; nearby homozygous snps") {

    val loci = "chr1:1297212-1297213"
    val variants = discoverGenotypesAtLoci(loci)

    variants.length should be(2)
    val variant1 = variants(0)
    variant1.referenceContig should be("chr1")
    variant1.start should be(1297212)
    Bases.basesToString(variant1.allele.refBases) should be("G")
    Bases.basesToString(variant1.allele.altBases) should be("C")

    val variant2 = variants(1)
    variant2.referenceContig should be("chr1")
    variant2.start should be(1297215)
    Bases.basesToString(variant2.allele.refBases) should be("A")
    Bases.basesToString(variant2.allele.altBases) should be("G")

  }

  test("test assembly caller: illumina platinum tests; 2 nearby homozygous snps") {

    val loci = "chr1:1316669-1316670"
    val variants = discoverGenotypesAtLoci(loci)

    variants.length should be(3)
    val variant1 = variants(0)
    variant1.referenceContig should be("chr1")
    variant1.start should be(1316647)
    Bases.basesToString(variant1.allele.refBases) should be("C")
    Bases.basesToString(variant1.allele.altBases) should be("T")

    val variant2 = variants(1)
    variant2.referenceContig should be("chr1")
    variant2.start should be(1316669)
    Bases.basesToString(variant2.allele.refBases) should be("C")
    Bases.basesToString(variant2.allele.altBases) should be("G")

    val variant3 = variants(2)
    variant3.referenceContig should be("chr1")
    variant3.start should be(1316673)
    Bases.basesToString(variant3.allele.refBases) should be("C")
    Bases.basesToString(variant3.allele.altBases) should be("T")

  }

  test("test assembly caller: illumina platinum tests; het snp") {

    val loci = "chr1:1342611-1342612"
    val variants = discoverGenotypesAtLoci(loci)

    variants.length should be(1)
    val variant = variants(0)
    variant.referenceContig should be("chr1")
    variant.start should be(1342611)
    Bases.basesToString(variant.allele.refBases) should be("G")
    Bases.basesToString(variant.allele.altBases) should be("C")
  }

  test("test assembly caller: illumina platinum tests; homozygous deletion") {

    val loci = "chr1:1296368-1296369"
    val variants = discoverGenotypesAtLoci(loci)

    variants.length should be(1)
    val variant = variants(0)
    variant.referenceContig should be("chr1")
    variant.start should be(1296368)
    Bases.basesToString(variant.allele.refBases) should be("GAC")
    Bases.basesToString(variant.allele.altBases) should be("G")
  }

  test("test assembly caller: illumina platinum tests; homozygous deletion 2") {

    val loci = "chr1:1303426-1303427"
    val variants = discoverGenotypesAtLoci(loci)

    variants.length should be(1)
    val variant = variants(0)
    variant.referenceContig should be("chr1")
    variant.start should be(1303426)
    Bases.basesToString(variant.allele.refBases) should be("ACT")
    Bases.basesToString(variant.allele.altBases) should be("A")
  }

  test("test assembly caller: illumina platinum tests; homozygous insertion") {

    val loci = "chr1:1321298-1321299"
    val variants = discoverGenotypesAtLoci(loci)

    variants.length should be(1)
    val variant = variants(0)
    variant.referenceContig should be("chr1")
    variant.start should be(1321298)
    Bases.basesToString(variant.allele.refBases) should be("A")
    Bases.basesToString(variant.allele.altBases) should be("AG")
  }

  test("test assembly caller: illumina platinum tests; homozygous insertion 2") {

    val loci = "chr1:1302671-1302672"
    val variants = discoverGenotypesAtLoci(loci)

    variants.length should be(1)
    val variant = variants(0)
    variant.referenceContig should be("chr1")
    variant.start should be(1302671)
    Bases.basesToString(variant.allele.refBases) should be("A")
    Bases.basesToString(variant.allele.altBases) should be("AGT")
  }

  test("test assembly caller: empty region") {

    val loci = "chr1:1303917-1303918"
    val variants = discoverGenotypesAtLoci(loci)
    variants.length should be(0)
  }

  test("test assembly caller: full test") {

    val loci = "chr1:772754-1417918"
    val variants = discoverGenotypesAtLoci(loci)
    println(variants.length)

    val outputGenotypes =
      variants.flatMap(AlleleConversions.calledAlleleToADAMGenotype)
    variants.filter(v => v.allele.refBases.isEmpty || v.allele.altBases.isEmpty).foreach(println)
    args.variantOutput = TestUtil.tmpFileName(suffix=".vcf")
    Common.writeVariantsFromArguments(args, sc.parallelize(outputGenotypes))
  }

  test("test assembly caller: homzygous snp in a repeat region") {

    val loci = "chr1:789255-789256"
    val variants = discoverGenotypesAtLoci(loci)
    variants.length should be(1)
    val variant = variants(0)
    variant.referenceContig should be("chr1")
    variant.start should be(789255)
    Bases.basesToString(variant.allele.refBases) should be("T")
    Bases.basesToString(variant.allele.altBases) should be("C")

  }



}
