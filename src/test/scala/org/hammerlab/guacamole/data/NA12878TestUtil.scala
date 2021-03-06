package org.hammerlab.guacamole.data

import org.hammerlab.guacamole.util.TestUtil.resourcePath

object NA12878TestUtil {
  // See illumina-platinum-na12878/run_other_callers.readme.sh for how these files were generated
  val subsetBam = resourcePath("illumina-platinum-na12878/NA12878.10k_variants.plus_chr1_3M-3.1M.bam")
  val expectedCallsVCF = resourcePath("illumina-platinum-na12878/NA12878.subset.vcf")
  val chr1PrefixFasta = resourcePath("illumina-platinum-na12878/chr1.prefix.fa")
}
