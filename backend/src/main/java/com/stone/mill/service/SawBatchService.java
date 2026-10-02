package com.stone.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stone.mill.common.BizException;
import com.stone.mill.common.ConflictException;
import com.stone.mill.dto.Dtos.BatchCloseReq;
import com.stone.mill.dto.Dtos.BatchCreateReq;
import com.stone.mill.dto.Dtos.DiffResolveReq;
import com.stone.mill.dto.Dtos.HandoverReq;
import com.stone.mill.dto.Dtos.OutputCorrectReq;
import com.stone.mill.dto.Dtos.OutputRegisterReq;
import com.stone.mill.dto.Dtos.OutputVoidReq;
import com.stone.mill.dto.Dtos.ReassignReq;
import com.stone.mill.dto.Queries.SawBatchQuery;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.dto.Views.SawBatchView;
import com.stone.mill.dto.Views.SawChangeView;
import com.stone.mill.dto.Views.SawDiffView;
import com.stone.mill.dto.Views.SawEventView;
import com.stone.mill.dto.Views.SawOutputView;
import com.stone.mill.dto.Views.SawPlanView;
import com.stone.mill.dto.Views.SlabLineageView;
import com.stone.mill.entity.Block;
import com.stone.mill.entity.CuttingOrder;
import com.stone.mill.entity.LayoutItem;
import com.stone.mill.entity.SawBatch;
import com.stone.mill.entity.SawChange;
import com.stone.mill.entity.SawDiff;
import com.stone.mill.entity.SawEvent;
import com.stone.mill.entity.SawOutput;
import com.stone.mill.entity.SawPlan;
import com.stone.mill.entity.Slab;
import com.stone.mill.repository.BlockRepository;
import com.stone.mill.repository.CuttingOrderRepository;
import com.stone.mill.repository.LayoutRepository;
import com.stone.mill.repository.SawBatchRepository;
import com.stone.mill.repository.SequenceRepository;
import com.stone.mill.repository.SlabRepository;

/**
 * 模块八：锯切批次交接与产出谱系。
 *
 * <p>业务主线：
 * <ol>
 *   <li><b>开锯 {@link #create}</b>：建立批次并冻结工单 / 荒料 / 锯切方式 / 班组 / 排板顺序与计划尺寸，
 *       快照此后不随基础资料改写；工单同步进入加工中、荒料置加工中。</li>
 *   <li><b>逐片登记 {@link #registerOutput}</b>：扫码交接逐片登记大板编号 / 实际尺寸 / 顺序 /
 *       产出类型 / 操作人 / 时间。client_token 唯一保证断网重试 / 重复扫码幂等；
 *       live_slab_id 唯一保证同一大板只能归一个批次（先到先得，后到方 409 并看到当前归属）。</li>
 *   <li><b>交接 {@link #handover}</b>：班组先完成交接，批次 → WAIT_CHECK；系统比较计划与实产，
 *       缺号 / 重复号 / 尺寸超差 / 数量不符 / 未解释锯耗进入差异待核，现场记录绝不丢弃。</li>
 *   <li><b>差异核清 {@link #resolveDiff} / 更正 / 改派 / 作废</b>：负责人逐项确认或更正归属，
 *       必填原因并只追加审计（保留前后值）；被后续批次返切引用的产出禁止作废。</li>
 *   <li><b>收口 {@link #close}</b>：差异全部核清才放行，单事务行锁批次 + 锁工单，
 *       一次性固化产出谱系并按合格实产（未作废 FINISHED 片数）推进工单完成数。
 *       两人同时收口 / 重复点击 / 中途失败只有一个有效结果，不会出现半成品状态。</li>
 * </ol>
 */
@Service
public class SawBatchService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SawBatchRepository sawRepository;
    private final CuttingOrderRepository orderRepository;
    private final BlockRepository blockRepository;
    private final SlabRepository slabRepository;
    private final LayoutRepository layoutRepository;
    private final SequenceRepository sequenceRepository;
    private final SawTxBridge txBridge;

    public SawBatchService(SawBatchRepository sawRepository, CuttingOrderRepository orderRepository,
                           BlockRepository blockRepository, SlabRepository slabRepository,
                           LayoutRepository layoutRepository, SequenceRepository sequenceRepository,
                           SawTxBridge txBridge) {
        this.sawRepository = sawRepository;
        this.orderRepository = orderRepository;
        this.blockRepository = blockRepository;
        this.slabRepository = slabRepository;
        this.layoutRepository = layoutRepository;
        this.sequenceRepository = sequenceRepository;
        this.txBridge = txBridge;
    }

    // ================================================================
    // 查询
    // ================================================================

    public PageResult<SawBatchView> page(SawBatchQuery query) {
        List<SawBatch> batches = sawRepository.search(query);
        List<SawBatchView> rows = batches.stream().map(b -> toView(b, false)).collect(Collectors.toList());
        return new PageResult<>(rows, sawRepository.count(query), query.getPage(), query.getSize());
    }

    public List<SawBatchView> listByOrder(Long orderId) {
        return sawRepository.listByOrder(orderId).stream()
                .map(b -> toView(b, true)).collect(Collectors.toList());
    }

    public SawBatchView detail(Long id) {
        SawBatch batch = requireBatch(id);
        return toView(batch, true);
    }

    /** 大板反查产出谱系：当前所属批次 + 历次更正（含跨批改派轨迹）。 */
    public SlabLineageView lineageBySlab(Long slabId) {
        List<SawOutput> outputs = sawRepository.listOutputsBySlab(slabId);
        SawOutput live = outputs.stream().filter(o -> "LIVE".equals(o.getStatus())).findFirst().orElse(null);
        SlabLineageView view = new SlabLineageView();
        view.setSlabId(slabId);
        Slab slab = slabRepository.findById(slabId).orElse(null);
        view.setSlabCode(slab == null ? null : slab.getSlabCode());

        Map<Long, SawBatch> batchMap = batchMap();
        if (live != null) {
            SawBatch batch = batchMap.get(live.getBatchId());
            view.setCurrentOutputId(live.getId());
            view.setCurrentBatchId(live.getBatchId());
            view.setCurrentBatchNo(batch == null ? null : batch.getBatchNo());
            view.setBatchStatus(batch == null ? null : batch.getStatus());
            view.setBatchStatusName(StoneDict.batchStatusName(batch == null ? null : batch.getStatus()));
            view.setOutputType(live.getOutputType());
            view.setOutputTypeName(StoneDict.outputTypeName(live.getOutputType()));
            view.setSeqNo(live.getSeqNo());
            view.setStatus(live.getStatus());
            view.setStatusName(StoneDict.outputStatusName(live.getStatus()));
            view.setProducedAt(live.getProducedAt());
            view.setBatchClosed(batch != null && "CLOSED".equals(batch.getStatus()));
        }
        List<Long> outputIds = outputs.stream().map(SawOutput::getId).collect(Collectors.toList());
        for (SawChange change : sawRepository.listChangesByOutputs(outputIds)) {
            view.getChanges().add(toChangeView(change, batchMap));
        }
        return view;
    }

    /** 工单谱系汇总（工单详情 / 列表用）：批次、待核差异、已固化合格实产。 */
    public Map<String, Object> orderLineageSummary(Long orderId) {
        List<SawBatch> batches = sawRepository.listByOrder(orderId);
        Map<String, Object> summary = new HashMap<>();
        summary.put("batchCount", batches.size());
        summary.put("closedBatchCount", batches.stream().filter(b -> "CLOSED".equals(b.getStatus())).count());
        summary.put("openDiffCount", batches.stream().mapToInt(b -> b.getOpenDiffCount() == null ? 0 : b.getOpenDiffCount()).sum());
        summary.put("closedFinishedTotal", sawRepository.sumClosedFinishedByOrder(orderId));
        return summary;
    }

    public boolean hasBatch(Long orderId) {
        return sawRepository.existsByOrder(orderId);
    }

    /**
     * 大板列表批量补谱系归属：slabId → 当前有效产出（含所属批次是否收口）。
     * 作废产出让出归属（live_slab_id 已为 NULL），这里只取 LIVE。
     */
    public Map<Long, Map<String, Object>> slabTrackedMap() {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        Map<Long, SawBatch> batches = batchMap();
        for (SawBatch b : batches.values()) {
            for (SawOutput o : sawRepository.listOutputs(b.getId())) {
                if ("LIVE".equals(o.getStatus()) && o.getSlabId() != null) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("batchNo", b.getBatchNo());
                    m.put("batchId", b.getId());
                    m.put("closed", "CLOSED".equals(b.getStatus()));
                    m.put("outputType", o.getOutputType());
                    result.put(o.getSlabId(), m);
                }
            }
        }
        return result;
    }

    // ================================================================
    // 开锯：建立批次 + 冻结排板顺序
    // ================================================================

    @Transactional
    public SawBatchView create(BatchCreateReq req) {
        CuttingOrder order = orderRepository.findById(req.getOrderId())
                .orElseThrow(() -> new BizException("加工工单不存在：id=" + req.getOrderId()));
        Block block = blockRepository.findById(order.getBlockId())
                .orElseThrow(() -> new BizException("工单关联荒料不存在"));
        if ("SETTLED".equals(order.getStatus()) || "DONE".equals(order.getStatus())) {
            throw new BizException("工单已" + ("SETTLED".equals(order.getStatus()) ? "结算" : "完工")
                    + "，不能再开锯切批次");
        }
        if (!StoneDict.isValidSawType(order.getSawType())) {
            throw new BizException("工单锯切方式取值非法，无法冻结");
        }
        // 同一工单已有未收口批次时不允许再开新批次（一次在切一批，避免班组交叉占用）
        if (sawRepository.existsByOrderAndStatus(order.getId(), "OPEN")
                || sawRepository.existsByOrderAndStatus(order.getId(), "WAIT_CHECK")) {
            throw new BizException("工单 " + order.getOrderNo() + " 已有未收口批次，请先交接核清，不能重复开锯");
        }

        LocalDateTime now = LocalDateTime.now();
        SawBatch batch = new SawBatch();
        batch.setOrderId(order.getId());
        batch.setBlockId(block.getId());
        batch.setSawType(order.getSawType());
        batch.setTeam(order.getTeam());
        // —— 冻结快照 ——
        batch.setSnapOrderNo(order.getOrderNo());
        batch.setSnapBlockCode(block.getBlockCode());
        batch.setSnapPlanCount(order.getPlanCount());
        batch.setSizeToleranceCm(req.getToleranceCm() == null || req.getToleranceCm() < 0
                ? 2 : req.getToleranceCm());
        batch.setStatus("OPEN");
        batch.setOpenDiffCount(0);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        long seq = sequenceRepository.nextValue("SB-" + now.getYear());
        batch.setBatchNo("SB-" + now.getYear() + "-" + String.format("%04d", seq));
        sawRepository.save(batch);
        sawRepository.flush();

        // —— 冻结当时的排板顺序（计划尺寸取排板方案，不再随排板重排变化） ——
        List<LayoutItem> layouts = layoutRepository.listByBlock(block.getId()).stream()
                .sorted(Comparator.comparing(LayoutItem::getSeqNo))
                .collect(Collectors.toList());
        if (layouts.isEmpty()) {
            throw new BizException("荒料 " + block.getBlockCode() + " 尚无排板方案，无法冻结锯切顺序，请先排板");
        }
        for (LayoutItem item : layouts) {
            SawPlan plan = new SawPlan();
            plan.setBatchId(batch.getId());
            plan.setSeqNo(item.getSeqNo());
            plan.setPlanLengthCm(item.getItemHeight());
            plan.setPlanWidthCm(item.getItemWidth());
            plan.setLayoutItemId(item.getId());
            Slab planned = item.getSlabId() == null ? null : slabRepository.findById(item.getSlabId()).orElse(null);
            plan.setSlabCode(planned == null ? null : planned.getSlabCode());
            sawRepository.savePlan(plan);
        }
        batch.setPlanCount(layouts.size());
        sawRepository.save(batch);

        addEvent(batch.getId(), "CREATE", null, "OPEN",
                "开锯建立：冻结荒料 " + block.getBlockCode() + "、工单 " + order.getOrderNo()
                        + "、" + StoneDict.sawTypeName(order.getSawType()) + "、顺序 1~" + layouts.size()
                        + "，容差 ±" + batch.getSizeToleranceCm() + "cm",
                req.getOperator(), now);

        // 工单 / 荒料进入加工中
        if ("PENDING".equals(order.getStatus())) {
            order.setStatus("PROCESSING");
            if (order.getStartDate() == null) {
                order.setStartDate(java.time.LocalDate.now());
            }
            orderRepository.save(order);
        }
        if ("PENDING".equals(block.getStatus()) || "DONE".equals(block.getStatus())) {
            block.setStatus("PROCESSING");
            blockRepository.save(block);
        }
        return detail(batch.getId());
    }

    // ================================================================
    // 逐片产出登记（幂等 + 全局唯一归属）
    // ================================================================

    @Transactional
    public SawBatchView registerOutput(Long batchId, OutputRegisterReq req) {
        SawBatch batch = requireBatch(batchId);
        if ("CLOSED".equals(batch.getStatus())) {
            throw new BizException("批次 " + batch.getBatchNo() + " 已收口固化，产出谱系不能再增改");
        }
        if (!StoneDict.isValidOutputType(req.getOutputType())) {
            throw new BizException("产出类型取值非法：FINISHED / REMNANT / SAW_LOSS");
        }
        boolean hasSlab = req.getSlabCode() != null && !req.getSlabCode().isBlank();
        if (!"SAW_LOSS".equals(req.getOutputType()) && !hasSlab) {
            throw new BizException("成品 / 可返切余料必须逐片登记大板编号");
        }
        // 锯耗允许先不填原因：现场先完成交接，未解释锯耗进入差异待核，由负责人追原因
        if (req.getSeqNo() != null) {
            boolean seqExists = sawRepository.listPlans(batch.getId()).stream()
                    .anyMatch(p -> req.getSeqNo().equals(p.getSeqNo()));
            if (!seqExists) {
                throw new BizException("顺序号 " + req.getSeqNo() + " 不在本批冻结排板顺序内");
            }
        }
        if (req.getActualLengthCm() != null && req.getActualLengthCm() <= 0) {
            throw new BizException("实际长度必须大于 0");
        }
        if (req.getActualWidthCm() != null && req.getActualWidthCm() <= 0) {
            throw new BizException("实际宽度必须大于 0");
        }
        LocalDateTime now = LocalDateTime.now();

        // 解析 / 建档大板（成品与余料）
        Slab slab = null;
        if (hasSlab) {
            slab = slabRepository.findByCode(req.getSlabCode().trim());
            if (slab == null) {
                if (req.getActualLengthCm() == null || req.getActualWidthCm() == null) {
                    throw new BizException("新大板 " + req.getSlabCode() + " 首次登记必须填写实际长宽");
                }
                slab = new Slab();
                slab.setSlabCode(req.getSlabCode().trim());
                slab.setBlockId(batch.getBlockId());
                slab.setLengthCm(req.getActualLengthCm());
                slab.setWidthCm(req.getActualWidthCm());
                slab.setThicknessCm(req.getThicknessCm() == null ? BigDecimal.valueOf(2.0) : req.getThicknessCm());
                slab.setAreaM2(BigDecimal.valueOf(req.getActualLengthCm())
                        .multiply(BigDecimal.valueOf(req.getActualWidthCm()))
                        .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
                slab.setFinish("POLISHED");
                slab.setGrade("REMNANT".equals(req.getOutputType()) ? "C" : "B");
                slab.setLocation("");
                slab.setStatus("INSTOCK");
                slab.setRemark("锯切批次 " + batch.getBatchNo() + " 交接扫码建档");
                slabRepository.save(slab);
                // 立即落库：编号唯一冲突在业务代码处抛 400，不拖成收口兜底 500
                slabRepository.flush();
            } else if (!slab.getBlockId().equals(batch.getBlockId())) {
                throw new BizException("大板 " + slab.getSlabCode() + " 属于荒料 "
                        + blockCode(slab.getBlockId()) + "，不能登记到本批（荒料 " + batch.getSnapBlockCode() + "）");
            }
        }

        SawOutput output = new SawOutput();
        output.setBatchId(batch.getId());
        output.setClientToken(req.getClientToken().trim());
        output.setOutputType(req.getOutputType());
        output.setSeqNo(req.getSeqNo());
        output.setActualLengthCm(req.getActualLengthCm());
        output.setActualWidthCm(req.getActualWidthCm());
        output.setThicknessCm(req.getThicknessCm());
        output.setSourceSlabId(req.getSourceSlabId());
        output.setOperator(req.getOperator());
        output.setNote(req.getNote());
        output.setStatus("LIVE");
        output.setProducedAt(parseTime(req.getProducedAt(), now));
        output.setCreatedAt(now);
        if (slab != null) {
            output.setSlabId(slab.getId());
            output.setSlabCode(slab.getSlabCode());
        }
        Long slabId = slab == null ? null : slab.getId();
        try {
            sawRepository.saveOutput(output);
            // 立即落库：token / live_slab 唯一冲突在业务代码处抛出，转幂等结果或 409，不拖成 500
            sawRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 当前事务必回滚（含可能同事务新建的大板）；冲突方读取走 REQUIRES_NEW 桥
            throw registerConflict(req, slabId);
        }

        addChange(batch.getId(), output.getId(), "REGISTER", null, batch.getId(),
                null, outputSnapshot(output),
                "交接扫码登记" + (req.getNote() == null ? "" : "：" + req.getNote()),
                req.getOperator(), now);

        // 交接后补登 / 重试期间新增的产出：立即重算一次差异
        if ("WAIT_CHECK".equals(batch.getStatus())) {
            recomputeDiffs(batch);
            sawRepository.save(batch);
        }
        batch.setUpdatedAt(now);
        sawRepository.save(batch);
        return detail(batch.getId());
    }

    /**
     * 唯一键冲突分流（当前事务必回滚，读冲突方必须走新事务桥）：
     * <ul>
     *   <li>撞 client_token：断网重试 / 重复扫码——同 token 直接幂等返回首次结果，不生成两份产出；
     *       token 相同但关键内容不同（复用了别人令牌）→ 400 明确报错，不静默顶替。</li>
     *   <li>撞 live_slab_id：跨批次重复扫码——保留先到记录，向后到人员 409 显示当前归属，不覆盖。</li>
     * </ul>
     */
    private RuntimeException registerConflict(OutputRegisterReq req, Long slabId) {
        SawOutput byToken = txBridge.findOutputByToken(req.getClientToken().trim());
        if (byToken != null) {
            if (sameSemantic(byToken, req)) {
                // 纯重试：回带首次登记所在批次，前端定位过去即可
                SawBatch first = txBridge.findBatch(byToken.getBatchId());
                return new ConflictException(
                        "该扫码已登记过（令牌 " + req.getClientToken() + "），首次登记在批次 "
                                + (first == null ? "" : first.getBatchNo()) + "，未重复生成产出",
                        "saw_output", idempotentPayload(byToken, first));
            }
            return new BizException("扫码令牌 " + req.getClientToken()
                    + " 已被其它大板登记占用，请更换现场令牌后再提交");
        }
        if (slabId != null) {
            SawOutput winner = txBridge.findLiveOutputBySlab(slabId);
            if (winner != null) {
                SawBatch owner = txBridge.findBatch(winner.getBatchId());
                return new ConflictException("大板 " + winner.getSlabCode() + " 已先登记到批次 "
                        + (owner == null ? "" : owner.getBatchNo())
                        + "（顺序" + winner.getSeqNo() + "，" + StoneDict.outputTypeName(winner.getOutputType())
                        + "，登记人 " + winner.getOperator() + "），保留先到记录；如属误登请在该批次做跨批改派",
                        "saw_output", ownerPayload(winner, owner));
            }
        }
        return new BizException("登记失败：数据冲突，请刷新后重试");
    }

    private boolean sameSemantic(SawOutput exist, OutputRegisterReq req) {
        boolean slabSame = (exist.getSlabCode() == null && (req.getSlabCode() == null || req.getSlabCode().isBlank()))
                || (exist.getSlabCode() != null && exist.getSlabCode().equals(req.getSlabCode() == null ? null : req.getSlabCode().trim()));
        return slabSame && exist.getOutputType().equals(req.getOutputType())
                && java.util.Objects.equals(exist.getSeqNo(), req.getSeqNo())
                && exist.getBatchId() != null;
    }

    // ================================================================
    // 交接：班组完成登记 → WAIT_CHECK，并做计划 vs 实产比较
    // ================================================================

    @Transactional
    public SawBatchView handover(Long batchId, HandoverReq req) {
        SawBatch batch = requireBatch(batchId);
        if (!"OPEN".equals(batch.getStatus())) {
            throw new BizException("批次已完成交接，当前：" + StoneDict.batchStatusName(batch.getStatus()));
        }
        List<SawOutput> outputs = liveOutputs(batch.getId());
        if (outputs.isEmpty()) {
            throw new BizException("还没有逐片产出登记，不能交接");
        }
        LocalDateTime now = LocalDateTime.now();
        String from = batch.getStatus();
        batch.setStatus("WAIT_CHECK");
        batch.setHandedOverBy(req == null ? null : req.getOperator());
        batch.setHandedOverAt(now);
        recomputeDiffs(batch);
        batch.setUpdatedAt(now);
        sawRepository.save(batch);
        int open = batch.getOpenDiffCount();
        addEvent(batch.getId(), "HANDOVER", from, "WAIT_CHECK",
                "交接完成：" + summarize(outputs) + "，差异 " + open + " 条"
                        + (open > 0 ? "待核，核清前不推进工单完成数" : "，计划吻合")
                        + (req != null && req.getNote() != null && !req.getNote().isBlank()
                        ? "；交接说明：" + req.getNote() : ""),
                req == null ? null : req.getOperator(), now);
        return detail(batch.getId());
    }

    // ================================================================
    // 差异逐项核清（必填原因）
    // ================================================================

    @Transactional
    public SawBatchView resolveDiff(Long diffId, DiffResolveReq req) {
        SawDiff diff = sawRepository.findDiff(diffId)
                .orElseThrow(() -> new BizException("差异不存在：id=" + diffId));
        SawBatch batch = requireBatch(diff.getBatchId());
        if ("CLOSED".equals(batch.getStatus())) {
            throw new BizException("批次已收口，差异不能再处理");
        }
        if (!"OPEN".equals(diff.getStatus())) {
            throw new BizException("该差异已核清，不能重复确认");
        }
        if (!StoneDict.isValidDiffResolution(req.getResolution()) || "FIX".equals(req.getResolution())) {
            throw new BizException("核清方式取值非法：CONFIRM 确认实际 / REASSIGN 更正归属 / VOID 作废误登"
                    + "（尺寸或原因更正请走「更正登记」，系统会自动核清）");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException("必须填写核清原因");
        }
        LocalDateTime now = LocalDateTime.now();

        switch (req.getResolution()) {
            case "CONFIRM" -> {
                // 负责人确认现场实际结果（如超差降级接收、锯耗情况属实、缺号/数量按实）：
                // 只关闭差异并留痕，产出保持 LIVE；重算看到该类型已有 RESOLVED 结论，不再复活。
            }
            case "REASSIGN" -> throw new BizException("更正归属请使用该产出的「跨批改派」操作，系统会自动核清对应差异");
            case "VOID" -> {
                // 差异明细上的 slabId 是涉及大板，真正要作废的是本批该大板的 LIVE 产出
                SawOutput target = liveOutputs(batch.getId()).stream()
                        .filter(o -> diff.getSlabId() != null && diff.getSlabId().equals(o.getSlabId()))
                        .findFirst().orElse(null);
                if (target == null) {
                    throw new BizException("该差异不对应具体可作废产出（缺号 / 数量 / 锯耗类），请用 CONFIRM 确认实际");
                }
                voidOutputInternal(batch, target, req.getReason(), req.getOperator(), now, "VOID");
            }
            default -> throw new BizException("核清方式取值非法");
        }

        diff.setStatus("RESOLVED");
        diff.setResolution(req.getResolution());
        diff.setResolveReason(req.getReason());
        diff.setResolvedBy(req.getOperator());
        diff.setResolvedAt(now);
        sawRepository.saveDiff(diff);

        // CONFIRM 不改编产出口径，但也要在审计上留一条确认记录
        if ("CONFIRM".equals(req.getResolution()) && diff.getSlabId() != null) {
            SawOutput target = liveOutputs(batch.getId()).stream()
                    .filter(o -> diff.getSlabId().equals(o.getSlabId())).findFirst().orElse(null);
            if (target != null) {
                addChange(batch.getId(), target.getId(), "CONFIRM", batch.getId(), batch.getId(),
                        outputSnapshot(target), outputSnapshot(target),
                        "核清差异「" + StoneDict.diffTypeName(diff.getDiffType()) + "」：" + req.getReason(),
                        req.getOperator(), now);
            }
        }

        // 作废等动作改变了产出集，重算剩余差异；已 RESOLVED 的不复活
        recomputeDiffs(batch);
        batch.setUpdatedAt(now);
        sawRepository.save(batch);
        return detail(batch.getId());
    }

    /** 更正登记内容（实际尺寸 / 顺序 / 锯耗原因）：保留前后值，超差 / 锯耗差异自动核清。 */
    @Transactional
    public SawBatchView correctOutput(Long outputId, OutputCorrectReq req) {
        SawOutput output = sawRepository.findOutput(outputId)
                .orElseThrow(() -> new BizException("产出不存在：id=" + outputId));
        SawBatch batch = requireBatch(output.getBatchId());
        if ("CLOSED".equals(batch.getStatus())) {
            throw new BizException("批次已收口固化，产出不能再更正");
        }
        if (!"LIVE".equals(output.getStatus())) {
            throw new BizException("已作废的产出不能更正");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException("必须填写更正原因");
        }
        if (req.getSeqNo() != null) {
            boolean seqExists = sawRepository.listPlans(batch.getId()).stream()
                    .anyMatch(p -> req.getSeqNo().equals(p.getSeqNo()));
            if (!seqExists) {
                throw new BizException("顺序号 " + req.getSeqNo() + " 不在本批冻结排板顺序内");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        String before = outputSnapshot(output);

        if (req.getSeqNo() != null) {
            output.setSeqNo(req.getSeqNo());
        }
        if (req.getActualLengthCm() != null && req.getActualLengthCm() > 0) {
            output.setActualLengthCm(req.getActualLengthCm());
        }
        if (req.getActualWidthCm() != null && req.getActualWidthCm() > 0) {
            output.setActualWidthCm(req.getActualWidthCm());
        }
        if (req.getThicknessCm() != null) {
            output.setThicknessCm(req.getThicknessCm());
        }
        if (req.getNote() != null) {
            output.setNote(req.getNote());
        }
        // 尺寸改动同步刷新大板实际尺寸与面积
        if (output.getSlabId() != null && req.getActualLengthCm() != null && req.getActualWidthCm() != null) {
            Slab slab = slabRepository.findById(output.getSlabId()).orElse(null);
            if (slab != null) {
                slab.setLengthCm(req.getActualLengthCm());
                slab.setWidthCm(req.getActualWidthCm());
                slab.setAreaM2(BigDecimal.valueOf(req.getActualLengthCm())
                        .multiply(BigDecimal.valueOf(req.getActualWidthCm()))
                        .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
                slabRepository.save(slab);
            }
        }
        sawRepository.saveOutput(output);

        // 自动核清：更正后已不再超差的 SIZE_TOL、已补原因的未解释锯耗
        autoResolveFixedDiffs(batch, output, req.getReason(), req.getOperator(), now);
        addChange(batch.getId(), output.getId(), "FIX_CORRECT", batch.getId(), batch.getId(),
                before, outputSnapshot(output), "更正登记内容：" + req.getReason(), req.getOperator(), now);

        recomputeDiffs(batch);
        batch.setUpdatedAt(now);
        sawRepository.save(batch);
        return detail(batch.getId());
    }

    /** 跨批次更正归属：把误登产出改派到真正批次，先到记录不被静默覆盖（需负责人显式操作 + 原因）。 */
    @Transactional
    public SawBatchView reassignOutput(Long outputId, ReassignReq req) {
        SawOutput output = sawRepository.findOutput(outputId)
                .orElseThrow(() -> new BizException("产出不存在：id=" + outputId));
        SawBatch fromBatch = requireBatch(output.getBatchId());
        SawBatch toBatch = requireBatch(req.getTargetBatchId());
        if (fromBatch.getId().equals(toBatch.getId())) {
            throw new BizException("目标批次与当前批次相同，无需改派");
        }
        if ("CLOSED".equals(fromBatch.getStatus()) || "CLOSED".equals(toBatch.getStatus())) {
            throw new BizException("已收口固化的批次不能再做跨批改派");
        }
        if (!"LIVE".equals(output.getStatus())) {
            throw new BizException("已作废产出不能改派");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException("必须填写改派原因");
        }
        if (output.getSlabId() == null) {
            throw new BizException("锯耗登记不属于具体大板，无需跨批改派（可在本批作废后重登）");
        }
        if (!toBatch.getBlockId().equals(output.getSlabId() == null ? null
                : slabRepository.findById(output.getSlabId()).map(Slab::getBlockId).orElse(null))) {
            throw new BizException("目标批次荒料与该大板不一致，不能改派");
        }
        if (req.getTargetSeqNo() != null) {
            final int targetSeq = req.getTargetSeqNo();
            if (sawRepository.listPlans(toBatch.getId()).stream().noneMatch(p -> targetSeq == p.getSeqNo())) {
                throw new BizException("顺序号 " + targetSeq + " 不在目标批次冻结排板顺序内");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        String before = outputSnapshot(output);
        Long fromId = fromBatch.getId();

        output.setBatchId(toBatch.getId());
        if (req.getTargetSeqNo() != null) {
            output.setSeqNo(req.getTargetSeqNo());
        }
        try {
            sawRepository.saveOutput(output);
            sawRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 目标批次已先登记了这块板：仍保留那边的先到记录
            SawOutput winner = txBridge.findLiveOutputBySlab(output.getSlabId());
            SawBatch owner = winner == null ? null : txBridge.findBatch(winner.getBatchId());
            throw new ConflictException("大板 " + output.getSlabCode() + " 在目标批次已存在先到登记"
                    + (owner == null ? "" : "（" + owner.getBatchNo() + "）") + "，不能覆盖；请先核那边记录",
                    "saw_output", ownerPayload(winner, owner));
        }

        addChange(toBatch.getId(), output.getId(), "REASSIGN", fromId, toBatch.getId(),
                before, outputSnapshot(output),
                "跨批改派：" + fromBatch.getBatchNo() + " → " + toBatch.getBatchNo() + "，原因：" + req.getReason(),
                req.getOperator(), now);

        // 两批差异都重算；源批次若因此缺号 / 数量变化，重新挂差异等待核清，不静默消失
        recomputeDiffs(fromBatch);
        recomputeDiffs(toBatch);
        fromBatch.setUpdatedAt(now);
        toBatch.setUpdatedAt(now);
        sawRepository.save(fromBatch);
        sawRepository.save(toBatch);
        return detail(toBatch.getId());
    }

    /** 作废误登产出（必填原因；被后续批次返切引用禁止作废）。 */
    @Transactional
    public SawBatchView voidOutput(Long outputId, OutputVoidReq req) {
        SawOutput output = sawRepository.findOutput(outputId)
                .orElseThrow(() -> new BizException("产出不存在：id=" + outputId));
        SawBatch batch = requireBatch(output.getBatchId());
        if ("CLOSED".equals(batch.getStatus())) {
            throw new BizException("批次已收口固化，产出不能作废；如确有错误请走质量与库存流程");
        }
        if (!"LIVE".equals(output.getStatus())) {
            throw new BizException("该产出已作废");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException("必须填写作废原因");
        }
        LocalDateTime now = LocalDateTime.now();
        voidOutputInternal(batch, output, req.getReason(), req.getOperator(), now, "VOID");
        recomputeDiffs(batch);
        batch.setUpdatedAt(now);
        sawRepository.save(batch);
        return detail(batch.getId());
    }

    private void voidOutputInternal(SawBatch batch, SawOutput output, String reason,
                                    String operator, LocalDateTime now, String changeType) {
        if (sawRepository.countReferencesToOutput(output.getId()) > 0) {
            throw new BizException("该产出已被后续批次作为返切来源引用，不能删除 / 作废（谱系链不可断）；"
                    + "如需更正请联系负责人评估后续批次");
        }
        String before = outputSnapshot(output);
        output.setStatus("VOID");
        output.setVoidReason(reason);
        output.setVoidBy(operator);
        output.setVoidAt(now);
        sawRepository.saveOutput(output);
        addChange(batch.getId(), output.getId(), changeType, batch.getId(), batch.getId(),
                before, outputSnapshot(output), "作废误登产出：" + reason, operator, now);
    }

    // ================================================================
    // 收口：差异全核清才放行；行锁 + 乐观锁单事务固化谱系并推进工单
    // ================================================================

    @Transactional
    public SawBatchView close(Long batchId, BatchCloseReq req) {
        // 行锁批次：两人同时收口在此串行，第二个看到 CLOSED 直接 409 回带已收口结果
        SawBatch batch = sawRepository.lockById(batchId);
        if (batch == null) {
            throw new BizException("锯切批次不存在：id=" + batchId);
        }
        if ("CLOSED".equals(batch.getStatus())) {
            throw new ConflictException("批次 " + batch.getBatchNo() + " 已收口（" + batch.getClosedBy()
                    + "），重复收口未生效，页面已刷新到固化谱系", "saw_batch",
                    Map.of("id", batch.getId(), "batchNo", batch.getBatchNo(), "status", "CLOSED"));
        }
        if (!"WAIT_CHECK".equals(batch.getStatus())) {
            throw new BizException("批次尚未交接，不能收口");
        }

        // 收口前强制再复核一次差异（防止核清动作与收口之间的遗漏），仍有 OPEN 一律拒绝
        recomputeDiffs(batch);
        int open = batch.getOpenDiffCount() == null ? 0 : batch.getOpenDiffCount();
        if (open > 0) {
            sawRepository.save(batch);
            throw new BizException("还有 " + open + " 条差异待核，必须全部核清后才能收口；"
                    + "现场记录已保留，工单完成数暂不推进");
        }

        List<SawOutput> outputs = sawRepository.listOutputs(batch.getId());
        List<SawOutput> live = outputs.stream().filter(o -> "LIVE".equals(o.getStatus())).collect(Collectors.toList());
        int finished = (int) live.stream().filter(o -> "FINISHED".equals(o.getOutputType())).count();
        int remnant = (int) live.stream().filter(o -> "REMNANT".equals(o.getOutputType())).count();
        int loss = (int) live.stream().filter(o -> "SAW_LOSS".equals(o.getOutputType())).count();
        int voidCount = (int) outputs.stream().filter(o -> "VOID".equals(o.getStatus())).count();
        LocalDateTime now = LocalDateTime.now();
        String from = batch.getStatus();
        batch.setStatus("CLOSED");
        // —— 一次性固化谱系计数，此后不再随产出表增改 ——
        batch.setClosedFinished(finished);
        batch.setClosedRemnant(remnant);
        batch.setClosedLoss(loss);
        batch.setClosedVoid(voidCount);
        batch.setClosedBy(req == null ? null : req.getOperator());
        batch.setClosedAt(now);
        batch.setClosedRemark(req == null ? null : req.getRemark());
        batch.setUpdatedAt(now);
        try {
            sawRepository.save(batch);
            // flush 让乐观锁 / 状态冲突立刻在本代码处抛出
            sawRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            SawBatch latest = txBridge.findBatch(batch.getId());
            throw new ConflictException("批次刚被其他人收口，只有一个收口生效，已刷新到固化结果",
                    "saw_batch", latest == null ? Map.of() : Map.of(
                            "id", latest.getId(), "batchNo", latest.getBatchNo(), "status", latest.getStatus()));
        }

        // —— 工单完成数：按该工单全部已收口批次合格实产之和重算（不是简单 +N）——
        applyOrderProgress(batch);

        addEvent(batch.getId(), "CLOSE", from, "CLOSED",
                "差异全部核清，收口固化产出谱系：成品 " + finished + " 片、可返切余料 " + remnant
                        + " 片、锯耗 " + loss + " 片、作废 " + voidCount
                        + " 片；按合格实产推进工单（余料 / 锯耗 / 作废不计完成数）"
                        + (req != null && req.getRemark() != null && !req.getRemark().isBlank()
                        ? "；结论：" + req.getRemark() : ""),
                req == null ? null : req.getOperator(), now);
        return detail(batch.getId());
    }

    /** 工单推进（与收口同一事务）：完成数 = 已收口批次合格实产之和；达计划即完工，荒料视情况完料。 */
    private void applyOrderProgress(SawBatch batch) {
        CuttingOrder order = orderRepository.findById(batch.getOrderId()).orElse(null);
        if (order == null) {
            return;
        }
        int qualified = sawRepository.sumClosedFinishedByOrder(order.getId());
        order.setDoneCount(qualified);
        int plan = order.getPlanCount() == null ? 0 : order.getPlanCount();
        if (qualified >= plan && !"SETTLED".equals(order.getStatus())) {
            order.setStatus("DONE");
            if (order.getFinishDate() == null) {
                order.setFinishDate(java.time.LocalDate.now());
            }
        } else if (!"SETTLED".equals(order.getStatus())) {
            order.setStatus("PROCESSING");
        }
        orderRepository.save(order);

        // 工单达标完工时荒料同步完料（与旧手工完工件同一口径；不到 100% 不动）
        if ("DONE".equals(order.getStatus())) {
            Block block = blockRepository.findById(order.getBlockId()).orElse(null);
            if (block != null && !"ARCHIVED".equals(block.getStatus()) && !"DONE".equals(block.getStatus())) {
                block.setStatus("DONE");
                blockRepository.save(block);
            }
        }
    }

    // ================================================================
    // 差异识别（计划 vs 实产）
    // ================================================================

    /**
     * 重算批次差异（交接、补登、更正、改派、作废后都调用）：
     * 只新增此前【从未挂过也从未核清】的差异；已 RESOLVED 的同类型结论不再复活。
     * 现场记录始终保留在产出表，差异只是待核清单——绝不丢弃。
     */
    private void recomputeDiffs(SawBatch batch) {
        List<SawPlan> plans = sawRepository.listPlans(batch.getId());
        List<SawOutput> live = liveOutputs(batch.getId());
        List<SawDiff> all = sawRepository.listDiffs(batch.getId());
        LocalDateTime now = LocalDateTime.now();
        int tolerance = batch.getSizeToleranceCm() == null ? 2 : batch.getSizeToleranceCm();

        Map<Integer, List<SawOutput>> bySeq = live.stream()
                .filter(o -> o.getSeqNo() != null)
                .collect(Collectors.groupingBy(SawOutput::getSeqNo));

        // 1) 逐顺序比较：缺号 / 重复号 / 尺寸超差
        for (SawPlan plan : plans) {
            List<SawOutput> seqOutputs = bySeq.getOrDefault(plan.getSeqNo(), List.of());

            if (seqOutputs.isEmpty()) {
                raiseDiff(batch, all, "MISSING_NO", plan.getSeqNo(), null, null,
                        "顺序" + plan.getSeqNo() + " 计划 " + plan.getPlanLengthCm() + "×" + plan.getPlanWidthCm()
                                + "cm，交接时无任何产出（缺号：既无成品 / 余料，也无锯耗登记）", now);
            } else {
                // 同顺序两片及以上挂大板（成品 / 余料）= 重复号
                long withSlab = seqOutputs.stream().filter(o -> o.getSlabId() != null).count();
                if (withSlab > 1) {
                    SawOutput first = seqOutputs.get(0);
                    raiseDiff(batch, all, "DUP_NO", plan.getSeqNo(), first.getSlabId(), first.getSlabCode(),
                            "顺序" + plan.getSeqNo() + " 出现 " + seqOutputs.size() + " 片产出（"
                                    + seqOutputs.stream().map(o -> o.getSlabCode() == null
                                    ? StoneDict.outputTypeName(o.getOutputType()) : o.getSlabCode())
                                    .collect(Collectors.joining("、")) + "），重复扫码或顺序错登", now);
                }
                // 尺寸超差：逐片比对冻结计划（锯耗无尺寸不比）
                for (SawOutput o : seqOutputs) {
                    if (o.getActualLengthCm() != null && o.getActualWidthCm() != null) {
                        int dl = o.getActualLengthCm() - plan.getPlanLengthCm();
                        int dw = o.getActualWidthCm() - plan.getPlanWidthCm();
                        if (Math.abs(dl) > tolerance || Math.abs(dw) > tolerance) {
                            raiseDiff(batch, all, "SIZE_TOL", plan.getSeqNo(), o.getSlabId(), o.getSlabCode(),
                                    "顺序" + plan.getSeqNo() + " 计划 " + plan.getPlanLengthCm() + "×"
                                            + plan.getPlanWidthCm() + "cm，实测 " + o.getActualLengthCm() + "×"
                                            + o.getActualWidthCm() + "cm，偏差 长" + signed(dl) + "cm / 宽"
                                            + signed(dw) + "cm，超出容差 ±" + tolerance + "cm", now);
                        }
                    }
                }
            }

            // 该顺序登记了锯耗但没写原因 = 未解释锯耗
            for (SawOutput o : seqOutputs) {
                if ("SAW_LOSS".equals(o.getOutputType()) && (o.getNote() == null || o.getNote().isBlank())) {
                    raiseDiff(batch, all, "SAW_LOSS_UNEXPLAINED", plan.getSeqNo(), null, null,
                            "顺序" + plan.getSeqNo() + " 登记为锯耗但未填写损耗原因与确认人，无法核对去向", now);
                }
            }
        }

        // 2) 计划外锯耗同样要解释
        for (SawOutput o : live) {
            if (o.getSeqNo() == null && "SAW_LOSS".equals(o.getOutputType())
                    && (o.getNote() == null || o.getNote().isBlank())) {
                raiseDiff(batch, all, "SAW_LOSS_UNEXPLAINED", null, null, null,
                        "存在计划外锯耗登记但未填写损耗原因", now);
            }
        }

        // 3) 数量不符：成品实产（含未核超差）与冻结顺序数不一致即挂一条汇总差异
        long finished = live.stream().filter(o -> "FINISHED".equals(o.getOutputType())).count();
        long remnant = live.stream().filter(o -> "REMNANT".equals(o.getOutputType())).count();
        long loss = live.stream().filter(o -> "SAW_LOSS".equals(o.getOutputType())).count();
        if (finished != plans.size()) {
            long missing = plans.stream().filter(p -> bySeq.getOrDefault(p.getSeqNo(), List.of()).isEmpty()).count();
            raiseDiff(batch, all, "COUNT_MISMATCH", null, null, null,
                    "本批冻结顺序 " + plans.size() + " 个：成品实产 " + finished + " 片、可返切余料 "
                            + remnant + " 片、锯耗 " + loss + " 片；成品比计划"
                            + (finished > plans.size() ? "多 " : "少 ")
                            + Math.abs(finished - plans.size()) + " 片（其中缺号顺序 " + missing + " 个）", now);
        }

        batch.setOpenDiffCount((int) sawRepository.listOpenDiffs(batch.getId()).size());
    }

    /** 新增差异：open_key 已在数据库去重，这里再按「同批次+同类型+同顺序+同板且从未核清」拦一道。 */
    private void raiseDiff(SawBatch batch, List<SawDiff> all, String type, Integer seq,
                           Long slabId, String slabCode, String detail, LocalDateTime now) {
        boolean everExisted = all.stream().anyMatch(d -> d.getDiffType().equals(type)
                && java.util.Objects.equals(d.getSeqNo(), seq)
                && java.util.Objects.equals(d.getSlabId(), slabId));
        if (everExisted) {
            return;
        }
        SawDiff diff = new SawDiff();
        diff.setBatchId(batch.getId());
        diff.setDiffType(type);
        diff.setSeqNo(seq);
        diff.setSlabId(slabId);
        diff.setSlabCode(slabCode);
        diff.setDetail(detail);
        diff.setStatus("OPEN");
        diff.setCreatedAt(now);
        try {
            sawRepository.saveDiff(diff);
            sawRepository.flush();
            all.add(diff);
        } catch (DataIntegrityViolationException ignore) {
            // 并发下 open_key 唯一兜底：别人已挂同一条，忽略
        }
    }

    /** 更正后自动核清对应 OPEN 差异（带原因与审计）。 */
    private void autoResolveFixedDiffs(SawBatch batch, SawOutput output, String reason,
                                       String operator, LocalDateTime now) {
        List<SawPlan> plans = sawRepository.listPlans(batch.getId());
        int tolerance = batch.getSizeToleranceCm() == null ? 2 : batch.getSizeToleranceCm();
        for (SawDiff diff : sawRepository.listOpenDiffs(batch.getId())) {
            boolean match = false;
            String resolution = null;
            if ("SIZE_TOL".equals(diff.getDiffType()) && output.getId() != null
                    && java.util.Objects.equals(diff.getSlabId(), output.getSlabId())
                    && output.getActualLengthCm() != null && output.getActualWidthCm() != null) {
                SawPlan plan = plans.stream().filter(p -> p.getSeqNo().equals(output.getSeqNo())).findFirst().orElse(null);
                if (plan != null && Math.abs(output.getActualLengthCm() - plan.getPlanLengthCm()) <= tolerance
                        && Math.abs(output.getActualWidthCm() - plan.getPlanWidthCm()) <= tolerance) {
                    match = true;
                    resolution = "FIX";
                }
            }
            if ("SAW_LOSS_UNEXPLAINED".equals(diff.getDiffType())
                    && "SAW_LOSS".equals(output.getOutputType())
                    && java.util.Objects.equals(diff.getSeqNo(), output.getSeqNo())
                    && output.getNote() != null && !output.getNote().isBlank()) {
                match = true;
                resolution = "CONFIRM";
            }
            if (match) {
                diff.setStatus("RESOLVED");
                diff.setResolution(resolution);
                diff.setResolveReason("更正后自动核清：" + reason);
                diff.setResolvedBy(operator);
                diff.setResolvedAt(now);
                sawRepository.saveDiff(diff);
            }
        }
    }

    // ================================================================
    // 视图组装
    // ================================================================

    private SawBatchView toView(SawBatch batch, boolean full) {
        SawBatchView v = new SawBatchView();
        v.setId(batch.getId());
        v.setBatchNo(batch.getBatchNo());
        v.setOrderId(batch.getOrderId());
        v.setOrderNo(batch.getSnapOrderNo());
        v.setBlockId(batch.getBlockId());
        v.setBlockCode(batch.getSnapBlockCode());
        v.setSawType(batch.getSawType());
        v.setSawTypeName(StoneDict.sawTypeName(batch.getSawType()));
        v.setTeam(batch.getTeam());
        v.setSnapOrderNo(batch.getSnapOrderNo());
        v.setSnapBlockCode(batch.getSnapBlockCode());
        v.setSnapPlanCount(batch.getSnapPlanCount());
        v.setPlanCount(batch.getPlanCount());
        v.setSizeToleranceCm(batch.getSizeToleranceCm());
        v.setStatus(batch.getStatus());
        v.setStatusName(StoneDict.batchStatusName(batch.getStatus()));
        v.setHandedOverBy(batch.getHandedOverBy());
        v.setHandedOverAt(batch.getHandedOverAt());
        v.setClosedFinished(batch.getClosedFinished());
        v.setClosedRemnant(batch.getClosedRemnant());
        v.setClosedLoss(batch.getClosedLoss());
        v.setClosedVoid(batch.getClosedVoid());
        v.setClosedBy(batch.getClosedBy());
        v.setClosedAt(batch.getClosedAt());
        v.setClosedRemark(batch.getClosedRemark());
        v.setOpenDiffCount(batch.getOpenDiffCount());
        v.setVersion(batch.getVersion());
        v.setCreatedAt(batch.getCreatedAt());
        v.setUpdatedAt(batch.getUpdatedAt());

        if (!full) {
            // 列表行也要带上待核数与实产汇总（轻量查询）
            List<SawOutput> outputs = sawRepository.listOutputs(batch.getId());
            fillCounts(v, outputs);
            v.setResolvedDiffCount((int) sawRepository.listDiffs(batch.getId()).stream()
                    .filter(d -> "RESOLVED".equals(d.getStatus())).count());
            return v;
        }

        List<SawPlan> plans = sawRepository.listPlans(batch.getId());
        List<SawOutput> outputs = sawRepository.listOutputs(batch.getId());
        Map<Integer, Long> seqCount = outputs.stream()
                .filter(o -> "LIVE".equals(o.getStatus()) && o.getSeqNo() != null)
                .collect(Collectors.groupingBy(SawOutput::getSeqNo, Collectors.counting()));
        Map<Long, Slab> slabs = slabMap();
        for (SawPlan plan : plans) {
            SawPlanView pv = new SawPlanView();
            pv.setId(plan.getId());
            pv.setSeqNo(plan.getSeqNo());
            pv.setPlanLengthCm(plan.getPlanLengthCm());
            pv.setPlanWidthCm(plan.getPlanWidthCm());
            pv.setSlabCode(plan.getSlabCode());
            pv.setOutputCount(seqCount.getOrDefault(plan.getSeqNo(), 0L).intValue());
            v.getPlans().add(pv);
        }

        Map<Long, SawBatch> batchMap = batchMap();
        int tolerance = batch.getSizeToleranceCm() == null ? 2 : batch.getSizeToleranceCm();
        for (SawOutput o : outputs) {
            v.getOutputs().add(toOutputView(o, plansMap(plans), slabs, tolerance));
        }
        fillCounts(v, outputs);
        List<SawDiff> diffs = sawRepository.listDiffs(batch.getId());
        for (SawDiff d : diffs) {
            v.getDiffs().add(toDiffView(d));
        }
        v.setResolvedDiffCount((int) diffs.stream().filter(d -> "RESOLVED".equals(d.getStatus())).count());
        for (SawChange c : sawRepository.listChanges(batch.getId())) {
            v.getChanges().add(toChangeView(c, batchMap));
        }
        for (SawEvent e : sawRepository.listEvents(batch.getId())) {
            v.getEvents().add(toEventView(e));
        }
        return v;
    }

    private void fillCounts(SawBatchView v, List<SawOutput> outputs) {
        v.setLiveFinished((int) outputs.stream().filter(o -> "LIVE".equals(o.getStatus())
                && "FINISHED".equals(o.getOutputType())).count());
        v.setLiveRemnant((int) outputs.stream().filter(o -> "LIVE".equals(o.getStatus())
                && "REMNANT".equals(o.getOutputType())).count());
        v.setLiveLoss((int) outputs.stream().filter(o -> "LIVE".equals(o.getStatus())
                && "SAW_LOSS".equals(o.getOutputType())).count());
        v.setLiveVoid((int) outputs.stream().filter(o -> "VOID".equals(o.getStatus())).count());
    }

    private SawOutputView toOutputView(SawOutput o, Map<Integer, SawPlan> plans,
                                       Map<Long, Slab> slabs, int tolerance) {
        SawOutputView v = new SawOutputView();
        v.setId(o.getId());
        v.setBatchId(o.getBatchId());
        v.setClientToken(o.getClientToken());
        v.setSlabId(o.getSlabId());
        v.setSlabCode(o.getSlabCode());
        v.setOutputType(o.getOutputType());
        v.setOutputTypeName(StoneDict.outputTypeName(o.getOutputType()));
        v.setSeqNo(o.getSeqNo());
        v.setActualLengthCm(o.getActualLengthCm());
        v.setActualWidthCm(o.getActualWidthCm());
        v.setThicknessCm(o.getThicknessCm());
        v.setSourceSlabId(o.getSourceSlabId());
        Slab source = o.getSourceSlabId() == null ? null : slabs.get(o.getSourceSlabId());
        v.setSourceSlabCode(source == null ? null : source.getSlabCode());
        v.setSourceOutputId(o.getSourceOutputId());
        v.setOperator(o.getOperator());
        v.setProducedAt(o.getProducedAt());
        v.setNote(o.getNote());
        v.setStatus(o.getStatus());
        v.setStatusName(StoneDict.outputStatusName(o.getStatus()));
        v.setVoidReason(o.getVoidReason());
        v.setVoidBy(o.getVoidBy());
        v.setVoidAt(o.getVoidAt());
        v.setCreatedAt(o.getCreatedAt());
        v.setReferencedByLaterBatch(sawRepository.countReferencesToOutput(o.getId()) > 0);
        if (o.getSeqNo() != null) {
            SawPlan plan = plans.get(o.getSeqNo());
            if (plan != null && o.getActualLengthCm() != null && o.getActualWidthCm() != null) {
                int dl = o.getActualLengthCm() - plan.getPlanLengthCm();
                int dw = o.getActualWidthCm() - plan.getPlanWidthCm();
                v.setSizeDeviation("长" + signed(dl) + "cm / 宽" + signed(dw) + "cm");
                v.setSizeOutOfTolerance(Math.abs(dl) > tolerance || Math.abs(dw) > tolerance);
            }
        }
        return v;
    }

    private SawDiffView toDiffView(SawDiff d) {
        SawDiffView v = new SawDiffView();
        v.setId(d.getId());
        v.setBatchId(d.getBatchId());
        v.setDiffType(d.getDiffType());
        v.setDiffTypeName(StoneDict.diffTypeName(d.getDiffType()));
        v.setSeqNo(d.getSeqNo());
        v.setSlabId(d.getSlabId());
        v.setSlabCode(d.getSlabCode());
        v.setDetail(d.getDetail());
        v.setStatus(d.getStatus());
        v.setStatusName(StoneDict.diffStatusName(d.getStatus()));
        v.setResolution(d.getResolution());
        v.setResolutionName(StoneDict.diffResolutionName(d.getResolution()));
        v.setResolveReason(d.getResolveReason());
        v.setResolvedBy(d.getResolvedBy());
        v.setResolvedAt(d.getResolvedAt());
        v.setCreatedAt(d.getCreatedAt());
        return v;
    }

    private SawChangeView toChangeView(SawChange c, Map<Long, SawBatch> batches) {
        SawChangeView v = new SawChangeView();
        v.setId(c.getId());
        v.setBatchId(c.getBatchId());
        SawBatch b = batches.get(c.getBatchId());
        v.setBatchNo(b == null ? null : b.getBatchNo());
        v.setOutputId(c.getOutputId());
        v.setChangeType(c.getChangeType());
        v.setChangeTypeName("FIX_CORRECT".equals(c.getChangeType())
                ? "更正登记" : StoneDict.changeTypeName(c.getChangeType()));
        v.setFromBatchId(c.getFromBatchId());
        SawBatch fb = c.getFromBatchId() == null ? null : batches.get(c.getFromBatchId());
        v.setFromBatchNo(fb == null ? null : fb.getBatchNo());
        v.setToBatchId(c.getToBatchId());
        SawBatch tb = c.getToBatchId() == null ? null : batches.get(c.getToBatchId());
        v.setToBatchNo(tb == null ? null : tb.getBatchNo());
        v.setBeforeJson(c.getBeforeJson());
        v.setAfterJson(c.getAfterJson());
        v.setReason(c.getReason());
        v.setOperator(c.getOperator());
        v.setCreatedAt(c.getCreatedAt());
        return v;
    }

    private SawEventView toEventView(SawEvent e) {
        SawEventView v = new SawEventView();
        v.setId(e.getId());
        v.setEventType(e.getEventType());
        v.setEventTypeName(StoneDict.batchEventName(e.getEventType()));
        v.setFromStatus(e.getFromStatus());
        v.setFromStatusName(StoneDict.batchStatusName(e.getFromStatus()));
        v.setToStatus(e.getToStatus());
        v.setToStatusName(StoneDict.batchStatusName(e.getToStatus()));
        v.setContent(e.getContent());
        v.setOperator(e.getOperator());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }

    // ================================================================
    // 内部工具
    // ================================================================

    private SawBatch requireBatch(Long id) {
        return sawRepository.findById(id)
                .orElseThrow(() -> new BizException("锯切批次不存在：id=" + id));
    }

    private List<SawOutput> liveOutputs(Long batchId) {
        return sawRepository.listOutputs(batchId).stream()
                .filter(o -> "LIVE".equals(o.getStatus()))
                .sorted(Comparator.comparing((SawOutput o) -> o.getSeqNo() == null
                        ? Integer.MAX_VALUE : o.getSeqNo()).thenComparing(SawOutput::getId))
                .collect(Collectors.toList());
    }

    private void addEvent(Long batchId, String type, String from, String to,
                          String content, String operator, LocalDateTime when) {
        SawEvent event = new SawEvent();
        event.setBatchId(batchId);
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setContent(content);
        event.setOperator(operator);
        event.setCreatedAt(when == null ? LocalDateTime.now() : when);
        sawRepository.saveEvent(event);
    }

    private void addChange(Long batchId, Long outputId, String type, Long fromBatch, Long toBatch,
                           String before, String after, String reason, String operator, LocalDateTime when) {
        SawChange change = new SawChange();
        change.setBatchId(batchId);
        change.setOutputId(outputId);
        change.setChangeType(type);
        change.setFromBatchId(fromBatch);
        change.setToBatchId(toBatch);
        change.setBeforeJson(before);
        change.setAfterJson(after);
        change.setReason(reason);
        change.setOperator(operator);
        change.setCreatedAt(when == null ? LocalDateTime.now() : when);
        sawRepository.saveChange(change);
    }

    private String outputSnapshot(SawOutput o) {
        Map<String, Object> m = new HashMap<>();
        m.put("slab", o.getSlabCode());
        m.put("type", o.getOutputType());
        m.put("seq", o.getSeqNo());
        m.put("size", o.getActualLengthCm() == null ? null : o.getActualLengthCm() + "x" + o.getActualWidthCm());
        m.put("status", o.getStatus());
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return m.toString();
        }
    }

    private Map<String, Object> idempotentPayload(SawOutput o, SawBatch b) {
        Map<String, Object> data = new HashMap<>();
        data.put("outputId", o.getId());
        data.put("batchId", o.getBatchId());
        data.put("batchNo", b == null ? null : b.getBatchNo());
        data.put("slabCode", o.getSlabCode());
        data.put("idempotent", true);
        return data;
    }

    private Map<String, Object> ownerPayload(SawOutput o, SawBatch b) {
        Map<String, Object> data = new HashMap<>();
        data.put("outputId", o == null ? null : o.getId());
        data.put("batchId", o == null ? null : o.getBatchId());
        data.put("batchNo", b == null ? null : b.getBatchNo());
        data.put("batchStatus", b == null ? null : b.getStatus());
        data.put("slabCode", o == null ? null : o.getSlabCode());
        data.put("seqNo", o == null ? null : o.getSeqNo());
        data.put("operator", o == null ? null : o.getOperator());
        return data;
    }

    private LocalDateTime parseTime(String text, LocalDateTime fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(text.trim().replace(' ', 'T'));
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(text.trim(), TS);
            } catch (Exception ex) {
                throw new BizException("产出时间格式应为 yyyy-MM-dd HH:mm:ss");
            }
        }
    }

    private String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    private String summarize(List<SawOutput> outputs) {
        long f = outputs.stream().filter(o -> "FINISHED".equals(o.getOutputType())).count();
        long r = outputs.stream().filter(o -> "REMNANT".equals(o.getOutputType())).count();
        long l = outputs.stream().filter(o -> "SAW_LOSS".equals(o.getOutputType())).count();
        List<String> parts = new ArrayList<>();
        parts.add("成品 " + f + " 片");
        if (r > 0) {
            parts.add("可返切余料 " + r + " 片");
        }
        if (l > 0) {
            parts.add("锯耗 " + l + " 片");
        }
        return String.join("、", parts);
    }

    private String blockCode(Long blockId) {
        return blockId == null ? "" : blockRepository.findById(blockId).map(Block::getBlockCode).orElse("");
    }

    private Map<Long, Slab> slabMap() {
        return slabRepository.listAll().stream()
                .collect(Collectors.toMap(Slab::getId, s -> s, (a, b) -> a));
    }

    private Map<Long, SawBatch> batchMap() {
        return sawRepository.listAll().stream()
                .collect(Collectors.toMap(SawBatch::getId, b -> b, (a, b) -> a));
    }

    private Map<Integer, SawPlan> plansMap(List<SawPlan> plans) {
        return plans.stream().collect(Collectors.toMap(SawPlan::getSeqNo, p -> p, (a, b) -> a));
    }
}
