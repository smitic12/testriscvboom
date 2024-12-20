package testriscvboom.v4.ifu

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

import testriscvboom.v4.common._
import testriscvboom.v4.util.{BoomCoreStringPrefix, MaskLower, WrapInc}

import scala.math.min





class TageResp extends Bundle {
  val ctr = UInt(3.W)
  val u   = UInt(2.W)
}

class TageTable(val nRows: Int, val tagSz: Int, val histLength: Int, val uBitPeriod: Int, val singlePorted: Boolean)
  (implicit p: Parameters) extends BoomModule()(p)
  with HasBoomFrontendParameters
{
  require(histLength <= globalHistoryLength)

  val nWrBypassEntries = 2
  val io = IO( new Bundle {
    val f1_req_valid = Input(Bool())
    val f1_req_pc    = Input(UInt(vaddrBitsExtended.W))
    val f1_req_ghist = Input(UInt(globalHistoryLength.W))

    val f2_resp = Output(Vec(bankWidth, Valid(new TageResp)))

    val update_mask    = Input(Vec(bankWidth, Bool()))
    val update_taken   = Input(Vec(bankWidth, Bool()))
    val update_alloc   = Input(Vec(bankWidth, Bool()))
    val update_old_ctr = Input(Vec(bankWidth, UInt(3.W)))

    val update_pc    = Input(UInt())
    val update_hist  = Input(UInt())

    val update_u_mask = Input(Vec(bankWidth, Bool()))
    val update_u = Input(Vec(bankWidth, UInt(2.W)))
  })

  def compute_folded_hist(hist: UInt, l: Int) = {
    val nChunks = (histLength + l - 1) / l
    val hist_chunks = (0 until nChunks) map {i =>
      hist(min((i+1)*l, histLength)-1, i*l)
    }
    hist_chunks.reduce(_^_)
  }

  def compute_tag_and_hash(unhashed_idx: UInt, hist: UInt) = {
    val idx_history = compute_folded_hist(hist, log2Ceil(nRows))
    val idx = (unhashed_idx ^ idx_history)(log2Ceil(nRows)-1,0)
    val tag_history = compute_folded_hist(hist, tagSz)
    val tag = ((unhashed_idx >> log2Ceil(nRows)) ^ tag_history)(tagSz-1,0)
    (idx, tag)
  }

  def inc_ctr(ctr: UInt, taken: Bool): UInt = {
    Mux(!taken, Mux(ctr === 0.U, 0.U, ctr - 1.U),
                Mux(ctr === 7.U, 7.U, ctr + 1.U))
  }


  val doing_reset = RegInit(true.B)
  val reset_idx = RegInit(0.U(log2Ceil(nRows).W))
  reset_idx := reset_idx + doing_reset
  when (reset_idx === (nRows-1).U) { doing_reset := false.B }


  class TageEntry extends Bundle {
    val valid = Bool() // TODO: Remove this valid bit
    val tag = UInt(tagSz.W)
    val ctr = UInt(3.W)
  }


  val tageEntrySz = 1 + tagSz + 3

  val (s1_hashed_idx, s1_tag) = compute_tag_and_hash(fetchIdx(io.f1_req_pc), io.f1_req_ghist)

  val us     = SyncReadMem(nRows, Vec(bankWidth*2, Bool()))
  val table  = SyncReadMem(nRows, Vec(bankWidth, UInt(tageEntrySz.W)))
  us.suggestName(s"tage_u_${histLength}")
  table.suggestName(s"tage_table_${histLength}")

  val mems = Seq((f"tage_l$histLength", nRows, bankWidth * tageEntrySz))

  val s2_tag       = RegNext(s1_tag)

  val s2_req_rtage = Wire(Vec(bankWidth, new TageEntry))
  val s2_req_rus = Wire(Vec(bankWidth*2, Bool()))
  val s2_req_rhits = VecInit(s2_req_rtage.map(e => e.valid && e.tag === s2_tag && !doing_reset))

  for (w <- 0 until bankWidth) {
    // This bit indicates the TAGE table matched here
    io.f2_resp(w).valid    := s2_req_rhits(w)
    io.f2_resp(w).bits.u   := Cat(s2_req_rus(w*2+1), s2_req_rus(w*2))
    io.f2_resp(w).bits.ctr := s2_req_rtage(w).ctr
  }

  val clear_u_ctr = RegInit(0.U((log2Ceil(uBitPeriod) + log2Ceil(nRows) + 1).W))
  when (doing_reset) { clear_u_ctr := 1.U } .otherwise { clear_u_ctr := clear_u_ctr + 1.U }

  val doing_clear_u = clear_u_ctr(log2Ceil(uBitPeriod)-1,0) === 0.U
  val clear_u_hi = clear_u_ctr(log2Ceil(uBitPeriod) + log2Ceil(nRows)) === 1.U
  val clear_u_lo = clear_u_ctr(log2Ceil(uBitPeriod) + log2Ceil(nRows)) === 0.U
  val clear_u_idx = clear_u_ctr >> log2Ceil(uBitPeriod)
  val clear_u_mask = VecInit((0 until bankWidth*2) map { i => if (i % 2 == 0) clear_u_lo else clear_u_hi }).asUInt

  val (update_idx, update_tag) = compute_tag_and_hash(fetchIdx(io.update_pc), io.update_hist)

  val update_wdata = Wire(Vec(bankWidth, new TageEntry))
  val wen = WireInit(doing_reset || io.update_mask.reduce(_||_))
  val rdata = if (singlePorted) table.read(s1_hashed_idx, !wen && io.f1_req_valid) else table.read(s1_hashed_idx, io.f1_req_valid)
  when (RegNext(wen) && singlePorted.B) {
    s2_req_rtage := 0.U.asTypeOf(Vec(bankWidth, new TageEntry))
  } .otherwise {
    s2_req_rtage := VecInit(rdata.map(_.asTypeOf(new TageEntry)))
  }
  when (wen) {
    val widx = Mux(doing_reset, reset_idx, update_idx)
    val wdata = Mux(doing_reset, VecInit(Seq.fill(bankWidth) { 0.U(tageEntrySz.W) }), VecInit(update_wdata.map(_.asUInt)))
    val wmask = Mux(doing_reset, ~(0.U(bankWidth.W)), io.update_mask.asUInt)
    table.write(widx, wdata, wmask.asBools)
  }

  val update_u_mask = VecInit((0 until bankWidth*2) map {i => io.update_u_mask(i / 2)})
  val update_u_wen = WireInit(doing_reset || doing_clear_u || update_u_mask.reduce(_||_))
  val u_rdata = if (singlePorted) {
    us.read(s1_hashed_idx, !update_u_wen && io.f1_req_valid)
  } else {
    us.read(s1_hashed_idx, io.f1_req_valid)
  }
  s2_req_rus := u_rdata
  when (update_u_wen) {
    val widx = Mux(doing_reset, reset_idx, Mux(doing_clear_u, clear_u_idx, update_idx))
    val wdata = Mux(doing_reset || doing_clear_u, VecInit(0.U((bankWidth*2).W).asBools), VecInit(io.update_u.asUInt.asBools))
    val wmask = Mux(doing_reset, ~(0.U((bankWidth*2).W)), Mux(doing_clear_u, clear_u_mask, update_u_mask.asUInt))
    us.write(widx, wdata, wmask.asBools)
  }





  val wrbypass_tags    = Reg(Vec(nWrBypassEntries, UInt(tagSz.W)))
  val wrbypass_idxs    = Reg(Vec(nWrBypassEntries, UInt(log2Ceil(nRows).W)))
  val wrbypass         = Reg(Vec(nWrBypassEntries, Vec(bankWidth, UInt(3.W))))
  val wrbypass_enq_idx = RegInit(0.U(log2Ceil(nWrBypassEntries).W))

  val wrbypass_hits    = VecInit((0 until nWrBypassEntries) map { i =>
    !doing_reset &&
    wrbypass_tags(i) === update_tag &&
    wrbypass_idxs(i) === update_idx
  })
  val wrbypass_hit     = wrbypass_hits.reduce(_||_)
  val wrbypass_hit_idx = PriorityEncoder(wrbypass_hits)

  for (w <- 0 until bankWidth) {
    update_wdata(w).ctr   := Mux(io.update_alloc(w),
      Mux(io.update_taken(w), 4.U,
                              3.U
      ),
      Mux(wrbypass_hit,       inc_ctr(wrbypass(wrbypass_hit_idx)(w), io.update_taken(w)),
                              inc_ctr(io.update_old_ctr(w), io.update_taken(w))
      )
    )
    update_wdata(w).valid := true.B
    update_wdata(w).tag   := update_tag
  }

  when (io.update_mask.reduce(_||_)) {
    when (wrbypass_hits.reduce(_||_)) {
      wrbypass(wrbypass_hit_idx) := VecInit(update_wdata.map(_.ctr))
    } .otherwise {
      wrbypass     (wrbypass_enq_idx) := VecInit(update_wdata.map(_.ctr))
      wrbypass_tags(wrbypass_enq_idx) := update_tag
      wrbypass_idxs(wrbypass_enq_idx) := update_idx
      wrbypass_enq_idx := WrapInc(wrbypass_enq_idx, nWrBypassEntries)
    }
  }



}


case class BoomTageParams(
  //                                           nSets, histLen, tagSz
  tableInfo: Seq[Tuple3[Int, Int, Int]] = Seq((  128,       2,     7),
                                              (  128,       4,     7),
                                              (  256,       8,     8),
                                              (  256,      16,     8),
                                              (  128,      32,     9),
                                              (  128,      64,     9)),
  uBitPeriod: Int = 2048,
  singlePorted: Boolean = false
)


class TageBranchPredictorBank(params: BoomTageParams = BoomTageParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  val tageUBitPeriod = params.uBitPeriod
  val tageNTables    = params.tableInfo.size

  class TageMeta extends Bundle
  {
    val provider      = Vec(bankWidth, Valid(UInt(log2Ceil(tageNTables).W)))
    val alt_differs   = Vec(bankWidth, Output(Bool()))
    val provider_u    = Vec(bankWidth, Output(UInt(2.W)))
    val provider_ctr  = Vec(bankWidth, Output(UInt(3.W)))
    val allocate      = Vec(bankWidth, Valid(UInt(log2Ceil(tageNTables).W)))
  }

  val f3_meta = Wire(new TageMeta)
  override val metaSz = f3_meta.asUInt.getWidth
  require(metaSz <= bpdMaxMetaLength)

  def inc_u(u: UInt, alt_differs: Bool, mispredict: Bool): UInt = {
    Mux(!alt_differs, u,
    Mux(mispredict, Mux(u === 0.U, 0.U, u - 1.U),
                    Mux(u === 3.U, 3.U, u + 1.U)))
  }

  val tt = params.tableInfo map {
    case (n, l, s) => {
      val t = Module(new TageTable(n, s, l, params.uBitPeriod, params.singlePorted))
      t.io.f1_req_valid := RegNext(io.f0_valid)
      t.io.f1_req_pc    := RegNext(bankAlign(io.f0_pc))
      t.io.f1_req_ghist := io.f1_ghist
      (t, t.mems)
    }
  }
  val tables = tt.map(_._1)
  val mems = tt.map(_._2).flatten

  val f2_resps = VecInit(tables.map(_.io.f2_resp))
  val f3_resps = RegNext(f2_resps)

  val s1_update_meta = s1_update.bits.meta.asTypeOf(new TageMeta)
  val s1_update_mispredict_mask = UIntToOH(s1_update.bits.cfi_idx.bits) &
    Fill(bankWidth, s1_update.bits.cfi_mispredicted)

  val s1_update_mask  = WireInit((0.U).asTypeOf(Vec(tageNTables, Vec(bankWidth, Bool()))))
  val s1_update_u_mask  = WireInit((0.U).asTypeOf(Vec(tageNTables, Vec(bankWidth, UInt(1.W)))))

  val s1_update_taken   = Wire(Vec(tageNTables, Vec(bankWidth, Bool())))
  val s1_update_old_ctr = Wire(Vec(tageNTables, Vec(bankWidth, UInt(3.W))))
  val s1_update_alloc   = Wire(Vec(tageNTables, Vec(bankWidth, Bool())))
  val s1_update_u       = Wire(Vec(tageNTables, Vec(bankWidth, UInt(2.W))))

  s1_update_taken   := DontCare
  s1_update_old_ctr := DontCare
  s1_update_alloc   := DontCare
  s1_update_u       := DontCare


  for (w <- 0 until bankWidth) {
    var s2_provided = false.B
    var s2_provider = 0.U
    var s2_alt_provided = false.B
    var s2_alt_provider = 0.U
    for (i <- 0 until tageNTables) {
      val hit = f2_resps(i)(w).valid
      s2_alt_provided = s2_alt_provided || (s2_provided && hit)
      s2_provided = s2_provided || hit

      s2_alt_provider = Mux(hit, s2_provider, s2_alt_provider)
      s2_provider = Mux(hit, i.U, s2_provider)
    }
    val s3_provided = RegNext(s2_provided)
    val s3_provider = RegNext(s2_provider)
    val s3_alt_provided = RegNext(s2_alt_provided)
    val s3_alt_provider = RegNext(s2_alt_provider)

    val prov = RegNext(f2_resps(s2_provider)(w).bits)
    val alt  = RegNext(f2_resps(s2_alt_provider)(w).bits)

    io.resp.f3(w).taken := Mux(s3_provided,
      Mux(prov.ctr === 3.U || prov.ctr === 4.U,
        Mux(s3_alt_provided, alt.ctr(2), io.resp_in(0).f3(w).taken),
        prov.ctr(2)),
      io.resp_in(0).f3(w).taken
    )
    f3_meta.provider(w).valid := s3_provided
    f3_meta.provider(w).bits  := s3_provider
    f3_meta.alt_differs(w)    := s3_alt_provided && alt.ctr(2) =/= io.resp.f3(w).taken
    f3_meta.provider_u(w)     := prov.u
    f3_meta.provider_ctr(w)   := prov.ctr

    // Create a mask of tables which did not hit our query, and also contain useless entries
    // and also uses a longer history than the provider
    val allocatable_slots = (
      VecInit(f3_resps.map(r => !r(w).valid && r(w).bits.u === 0.U)).asUInt &
      ~(MaskLower(UIntToOH(f3_meta.provider(w).bits)) & Fill(tageNTables, f3_meta.provider(w).valid))
    )
    val alloc_lfsr = random.LFSR(tageNTables max 2)

    val first_entry = PriorityEncoder(allocatable_slots)
    val masked_entry = PriorityEncoder(allocatable_slots & alloc_lfsr)
    val alloc_entry = Mux(allocatable_slots(masked_entry),
      masked_entry,
      first_entry)

    f3_meta.allocate(w).valid := allocatable_slots =/= 0.U
    f3_meta.allocate(w).bits  := alloc_entry

    val update_was_taken = (s1_update.bits.cfi_idx.valid &&
                            (s1_update.bits.cfi_idx.bits === w.U) &&
                            s1_update.bits.cfi_taken)
    when (s1_update.bits.br_mask(w) && s1_update.valid && s1_update.bits.is_commit_update) {
      when (s1_update_meta.provider(w).valid) {
        val provider = s1_update_meta.provider(w).bits

        s1_update_mask(provider)(w) := true.B
        s1_update_u_mask(provider)(w) := true.B

        val new_u = inc_u(s1_update_meta.provider_u(w),
                          s1_update_meta.alt_differs(w),
                          s1_update_mispredict_mask(w))
        s1_update_u      (provider)(w) := new_u
        s1_update_taken  (provider)(w) := update_was_taken
        s1_update_old_ctr(provider)(w) := s1_update_meta.provider_ctr(w)
        s1_update_alloc  (provider)(w) := false.B

      }
    }
  }
  when (s1_update.valid && s1_update.bits.is_commit_update && s1_update.bits.cfi_mispredicted && s1_update.bits.cfi_idx.valid) {
    val idx = s1_update.bits.cfi_idx.bits
    val allocate = s1_update_meta.allocate(idx)
    when (allocate.valid) {
      s1_update_mask (allocate.bits)(idx) := true.B
      s1_update_taken(allocate.bits)(idx) := s1_update.bits.cfi_taken
      s1_update_alloc(allocate.bits)(idx) := true.B

      s1_update_u_mask(allocate.bits)(idx) := true.B
      s1_update_u     (allocate.bits)(idx) := 0.U

    } .otherwise {
      val provider = s1_update_meta.provider(idx)
      val decr_mask = Mux(provider.valid, ~MaskLower(UIntToOH(provider.bits)), 0.U)

      for (i <- 0 until tageNTables) {
        when (decr_mask(i)) {
          s1_update_u_mask(i)(idx) := true.B
          s1_update_u     (i)(idx) := 0.U
        }
      }
    }

  }


  for (i <- 0 until tageNTables) {
    for (w <- 0 until bankWidth) {
      tables(i).io.update_mask(w)    := RegNext(s1_update_mask(i)(w))
      tables(i).io.update_taken(w)   := RegNext(s1_update_taken(i)(w))
      tables(i).io.update_alloc(w)   := RegNext(s1_update_alloc(i)(w))
      tables(i).io.update_old_ctr(w) := RegNext(s1_update_old_ctr(i)(w))

      tables(i).io.update_u_mask(w) := RegNext(s1_update_u_mask(i)(w))
      tables(i).io.update_u(w)      := RegNext(s1_update_u(i)(w))
    }
    tables(i).io.update_pc    := RegNext(s1_update.bits.pc)
    tables(i).io.update_hist  := RegNext(s1_update.bits.ghist)
  }


  //io.f3_meta := Cat(f3_meta.asUInt, micro.io.f3_meta(micro.metaSz-1,0), base.io.f3_meta(base.metaSz-1, 0))
  io.f3_meta := f3_meta.asUInt
}
