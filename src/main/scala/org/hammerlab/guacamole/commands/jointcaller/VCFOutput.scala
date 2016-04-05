package org.hammerlab.guacamole.commands.jointcaller

import java.util

import htsjdk.samtools.SAMSequenceDictionary
import htsjdk.variant.variantcontext._
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder
import htsjdk.variant.vcf._
import org.hammerlab.guacamole.commands.jointcaller.Input.{Analyte, TissueType}
import org.hammerlab.guacamole.commands.jointcaller.annotation.{MultiSampleSingleAlleleEvidenceAnnotation, SingleSampleSingleAlleleEvidenceAnnotation}
import org.hammerlab.guacamole.commands.jointcaller.evidence._
import org.hammerlab.guacamole.reference.ReferenceBroadcast

import scala.collection.{JavaConversions, mutable}

object VCFOutput {
  /**
   * Write a VCF to a local file.
   *
   * @param path file to write to
   * @param calls MultipleAllelesEvidenceAcrossSamples instances giving calls to write. Currently each allele contained
   *              therein results in a variant call.
   * @param inputs sample inputs to write genotypes for, can be a subset of the full set of inputs
   * @param includePooledNormal whether to include the pooled normal dna reads as its own sample
   * @param includePooledTumor whether to include the pooled tumor dna reads as its own sample
   * @param parameters variant calling parameters, written to VCF header
   * @param sequenceDictionary contig lengths
   * @param reference reference genome
   */
  def writeVcf(path: String,
               calls: Seq[MultiSampleMultiAlleleEvidence],
               inputs: InputCollection,
               includePooledNormal: Boolean,
               includePooledTumor: Boolean,
               parameters: Parameters,
               sequenceDictionary: SAMSequenceDictionary,
               reference: ReferenceBroadcast): Unit = {

    val writer = new VariantContextWriterBuilder()
      .setOutputFile(path)
      .setReferenceDictionary(sequenceDictionary)
      .build
    val headerLines = new util.HashSet[VCFHeaderLine]()
    headerLines.add(new VCFFormatHeaderLine("GT", 1, VCFHeaderLineType.String, "Genotype"))
    headerLines.add(new VCFFormatHeaderLine("AD", VCFHeaderLineCount.R, VCFHeaderLineType.Integer,
      "Allelic depths for the ref and alt alleles"))
    headerLines.add(new VCFFormatHeaderLine("PL", VCFHeaderLineCount.G, VCFHeaderLineType.Integer,
      "Phred scaled genotype likelihoods"))
    headerLines.add(new VCFFormatHeaderLine("DP", 1, VCFHeaderLineType.Integer, "Total depth"))
    headerLines.add(new VCFFormatHeaderLine("GQ", 1, VCFHeaderLineType.Integer, "Genotype quality"))

    // nonstandard
    headerLines.add(new VCFFormatHeaderLine("RL", 1, VCFHeaderLineType.String, "Unnormalized log10 genotype posteriors"))
    headerLines.add(new VCFFormatHeaderLine("VAF", 1, VCFHeaderLineType.Integer, "Variant allele frequency (percent)"))
    headerLines.add(new VCFFormatHeaderLine("TRIGGERED", 1, VCFHeaderLineType.Integer, "Did this sample trigger a call"))
    headerLines.add(new VCFFormatHeaderLine("ADP", 1, VCFHeaderLineType.String, "allelic depth as num postiive strand / num total"))
    headerLines.add(new VCFFormatHeaderLine("FF", VCFHeaderLineCount.UNBOUNDED, VCFHeaderLineType.String, "failing filters for this sample"))

    SingleSampleSingleAlleleEvidenceAnnotation.addVCFHeaders(headerLines)
    MultiSampleSingleAlleleEvidenceAnnotation.addVCFHeaders(headerLines)

    // INFO
    headerLines.add(new VCFInfoHeaderLine("TRIGGER", 1, VCFHeaderLineType.String,
      "Which likelihood ratio test triggered call"))
    headerLines.add(new VCFInfoHeaderLine("TUMOR_EXPRESSION", 1, VCFHeaderLineType.String,
      "Was there tumor RNA expression for the variant"))

    val header = new VCFHeader(headerLines,
      JavaConversions.seqAsJavaList(
        inputs.items.map(_.sampleName)
          ++ (if (includePooledNormal) Seq("pooled_normal") else Seq.empty)
          ++ (if (includePooledTumor) Seq("pooled_tumor") else Seq.empty)))
    header.setSequenceDictionary(sequenceDictionary)
    header.setWriteCommandLine(true)
    header.setWriteEngineHeaders(true)
    header.addMetaDataLine(new VCFHeaderLine("Caller", "Guacamole"))
    parameters.asStringPairs.foreach(kv => {
      header.addMetaDataLine(new VCFHeaderLine("parameter." + kv._1, kv._2))
    })
    writer.writeHeader(header)

    val sortedEvidences = calls.flatMap(_.alleleEvidences).sortBy(e => (e.allele.referenceContig, e.allele.start))
    sortedEvidences.foreach(evidence => {
      val variantContext = makeHtsjdVariantContext(evidence, inputs, includePooledNormal, includePooledTumor, reference)
      writer.add(variantContext)
    })
    writer.close()
  }

  /**
   * Make an htsjdk VariantContext for the given AlleleEvidenceAcrossSamples instance.
   *
   * @param samplesEvidence evidence for the call (or non-call)
   * @param subInputs subset of inputs to include in this VariantContext
   * @param includePooledNormal whether to include the pooled normal data as a sample in the result
   * @param includePooledTumor whether to include the pooled tumor data as a sample in the result
   * @param reference reference genome
   */
  def makeHtsjdVariantContext(samplesEvidence: MultiSampleSingleAlleleEvidence,
                              subInputs: InputCollection,
                              includePooledNormal: Boolean,
                              includePooledTumor: Boolean,
                              reference: ReferenceBroadcast): VariantContext = {

    val allele = samplesEvidence.allele

    def makeHtsjdkAllele(someAllele: String): Allele =
      Allele.create(someAllele, someAllele == allele.ref)

    def allelicDepthString(depths: Map[String, Int]): String = {
      val allAlleles = (Seq(allele.ref, allele.alt) ++ depths.keys.toSeq).distinct
      allAlleles.map(x => "%s=%d".format(if (x.isEmpty) "." else x, depths.getOrElse(x, 0))).mkString(" ")
    }

    val variantGenotypeAlleles = Seq(allele.ref, allele.alt)

    val effectiveInputs = subInputs.items ++
      (if (includePooledNormal)
        Seq(Input(samplesEvidence.normalDNAPooledIndex, "pooled_normal", "[no path]", TissueType.Normal, Analyte.DNA))
      else Seq.empty) ++
      (if (includePooledTumor)
        Seq(Input(samplesEvidence.tumorDNAPooledIndex, "pooled_tumor", "[no path]", TissueType.Tumor, Analyte.DNA))
      else Seq.empty)

    val genotypes = effectiveInputs.map(input => {
      def alleleToString(x: String) = if (x.isEmpty) "." else x
      def mixtureToString(mixture: Map[String, Double]) = mixture.map(pair => {
        "%s->%.2f".format(alleleToString(pair._1), pair._2)
      }).mkString(" ")

      val evidence = samplesEvidence.allEvidences(input.index)
      val genotypeBuilder = new GenotypeBuilder()
      genotypeBuilder.name(input.sampleName)
      (input.tissueType, input.analyte) match {
        case (TissueType.Normal, Analyte.DNA) => {
          val germlineEvidence = samplesEvidence.allEvidences(input.index).asInstanceOf[NormalDNASingleSampleSingleAlleleEvidence]
          val posteriors = samplesEvidence.perNormalSampleGermlinePosteriors(input.index)
          val alleleGenotype = posteriors.maxBy(_._2)._1
          genotypeBuilder.alleles(JavaConversions.seqAsJavaList(
            Seq(makeHtsjdkAllele(alleleGenotype._1), makeHtsjdkAllele(alleleGenotype._2))))
          genotypeBuilder.attribute("RL",
            posteriors.map(pair => "[%s/%s]->%.8g".format(pair._1._1, pair._1._2, pair._2)).mkString(" "))
          genotypeBuilder
            .AD(Seq(allele.ref, allele.alt).map(allele => germlineEvidence.allelicDepths.getOrElse(allele, 0)).toArray)
            .DP(germlineEvidence.depth)
            .attribute("ADP", allelicDepthString(germlineEvidence.allelicDepths))
            .attribute("VAF", germlineEvidence.vaf)
        }
        case (TissueType.Tumor, Analyte.DNA) => {
          val tumorEvidence = samplesEvidence.allEvidences(input.index).asInstanceOf[TumorDNASingleSampleSingleAlleleEvidence]
          val posteriors = samplesEvidence.perTumorDnaSampleSomaticPosteriors(input.index)
          val thisSampleTriggered = samplesEvidence.tumorDnaSampleIndicesTriggered.contains(input.index)
          val sampleGenotype = samplesEvidence.parameters.somaticGenotypePolicy match {
            case Parameters.SomaticGenotypePolicy.Presence => {
              // Call an alt if there is any variant evidence in this sample and no other alt allele has more evidence.
              val topTwoAlleles = tumorEvidence.allelicDepths.filter(_._2 > 0).toSeq.sortBy(-1 * _._2).map(_._1).take(2).toSet
              topTwoAlleles match {
                case x if x == Set(allele.ref, allele.alt) => Seq(allele.ref, allele.alt)
                case x if x == Set(allele.ref) || x == Set(allele.alt) => Seq(x.head, x.head)
                case _ => Seq(allele.ref, allele.ref) // fall back on hom ref
              }
            }
            case Parameters.SomaticGenotypePolicy.Trigger => {
              // Call an alt if this sample triggered a call.
              if (thisSampleTriggered)
                Seq(allele.ref, allele.alt)
              else
                Seq(allele.ref, allele.ref)
            }
          }
          genotypeBuilder.alleles(JavaConversions.seqAsJavaList(sampleGenotype.map(makeHtsjdkAllele _)))
          genotypeBuilder.attribute("RL",
            posteriors.map(pair => "[%s]->%.8g".format(mixtureToString(pair._1), pair._2)).mkString(" "))
          genotypeBuilder.attribute("TRIGGERED", if (thisSampleTriggered) "TRIGGER" else "NO")

          genotypeBuilder
            .attribute("ADP", allelicDepthString(tumorEvidence.allelicDepths))
            .attribute("VAF", tumorEvidence.vaf)
            .AD(Seq(allele.ref, allele.alt).map(allele => tumorEvidence.allelicDepths.getOrElse(allele, 0)).toArray)
            .DP(tumorEvidence.depth)
        }
        case (TissueType.Tumor, Analyte.RNA) => {
          val tumorEvidence = samplesEvidence.allEvidences(input.index).asInstanceOf[TumorRNASingleSampleSingleAlleleEvidence]
          val posteriors = samplesEvidence.perTumorRnaSampleSomaticPosteriors(input.index)
          val thisSampleExpressed = samplesEvidence.tumorRnaSampleExpressed.contains(input.index)
          val sampleGenotype = if (thisSampleExpressed) Seq(allele.ref, allele.alt) else Seq(allele.ref, allele.ref)
          genotypeBuilder.alleles(JavaConversions.seqAsJavaList(sampleGenotype.map(makeHtsjdkAllele _)))
          genotypeBuilder.attribute("RL",
            posteriors.map(pair => "[%s]->%.8g".format(mixtureToString(pair._1), pair._2)).mkString(" "))
          genotypeBuilder.attribute("TRIGGERED", if (thisSampleExpressed) "EXPRESSED" else "NO")

          genotypeBuilder
            .attribute("ADP", allelicDepthString(tumorEvidence.allelicDepths))
            .attribute("VAF", tumorEvidence.vaf)
            .AD(Seq(allele.ref, allele.alt).map(allele => tumorEvidence.allelicDepths.getOrElse(allele, 0)).toArray)
            .DP(tumorEvidence.depth)
        }
        case other => throw new NotImplementedError("Not supported: %s %s".format(other._1.toString, other._2.toString))
      }
      evidence.annotations.foreach({ case (name, annotation) => annotation.addInfoToVCF(genotypeBuilder) })
      if (evidence.failingFilters.nonEmpty) {
        genotypeBuilder.attribute("FF", JavaConversions.asJavaCollection(evidence.failingFilters.map(_._1)))
      }
      genotypeBuilder.make
    })

    val triggersBuilder = mutable.ArrayBuffer.newBuilder[String]
    if (samplesEvidence.isGermlineCall) {
      triggersBuilder += "GERMLINE_POOLED"
    } else {
      if (samplesEvidence.tumorDnaSampleIndicesTriggered.contains(samplesEvidence.tumorDNAPooledIndex)) {
        triggersBuilder += "SOMATIC_POOLED"
      }
      if (samplesEvidence.tumorDnaSampleIndicesTriggered.exists(_ != samplesEvidence.tumorDNAPooledIndex)) {
        triggersBuilder += "SOMATIC_INDIVIDUAL"
      }
    }

    val triggers = triggersBuilder.result()
    val variantContextBuilder = new VariantContextBuilder()
      .chr(allele.referenceContig)
      .start(allele.start + 1) // +1 for one based based (inclusive)
      .stop(allele.end) // +1 for one-based and -1 for inclusive
      .genotypes(JavaConversions.seqAsJavaList(genotypes))
      .alleles(JavaConversions.seqAsJavaList(variantGenotypeAlleles.distinct.map(makeHtsjdkAllele _)))
      .attribute("TRIGGER", if (triggers.nonEmpty) triggers.mkString("+") else "NONE")
      .attribute("TUMOR_EXPRESSION", if (samplesEvidence.tumorRnaSampleExpressed.nonEmpty) "YES" else "NO")

    val failingFilterNames = samplesEvidence.failingFilterNames
    if (failingFilterNames.isEmpty) {
      if (samplesEvidence.isCall) {
        variantContextBuilder.passFilters()
      }
    } else {
      variantContextBuilder.filters(new util.HashSet[String](JavaConversions.asJavaCollection(failingFilterNames)))
    }
    variantContextBuilder.make
  }
}