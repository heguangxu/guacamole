package org.hammerlab.guacamole.reads

import org.hammerlab.guacamole.util.BasesUtil._
import org.hammerlab.guacamole.util.GuacFunSuite

class UnmappedReadSuite extends GuacFunSuite {

  test("unmappedread is not mapped") {
    val read = UnmappedRead(
      "read1",
      "TCGACCCTCGA",
      Array[Byte]((10 to 20).map(_.toByte): _*),
      isDuplicate = true,
      123,
      failedVendorQualityChecks = false,
      isPaired = false
    )

    read.isMapped should be(false)
    read.asInstanceOf[Read].isMapped should be(false)

    val collectionMappedReads: Seq[Read] = Seq(read)
    collectionMappedReads(0).isMapped should be(false)
  }
}
