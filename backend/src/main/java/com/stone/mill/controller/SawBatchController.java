package com.stone.mill.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stone.mill.common.ApiResponse;
import com.stone.mill.dto.Dtos.BatchCloseReq;
import com.stone.mill.dto.Dtos.BatchCreateReq;
import com.stone.mill.dto.Dtos.DiffResolveReq;
import com.stone.mill.dto.Dtos.HandoverReq;
import com.stone.mill.dto.Dtos.OutputCorrectReq;
import com.stone.mill.dto.Dtos.OutputRegisterReq;
import com.stone.mill.dto.Dtos.OutputVoidReq;
import com.stone.mill.dto.Dtos.ReassignReq;
import com.stone.mill.dto.Queries.SawBatchQuery;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.dto.Views.SawBatchView;
import com.stone.mill.dto.Views.SlabLineageView;
import com.stone.mill.service.SawBatchService;

import jakarta.validation.Valid;

/**
 * 模块八：锯切批次交接与产出谱系。
 *
 * <p>开锯冻结 → 逐片扫码交接（断网重试幂等 / 跨批次重复先到先得）→ 差异待核
 * → 逐项核清（必填原因留痕）→ 收口一次性固化谱系并按合格实产推进工单。
 */
@RestController
@RequestMapping("/api/saw-batches")
public class SawBatchController {

    private final SawBatchService sawBatchService;

    public SawBatchController(SawBatchService sawBatchService) {
        this.sawBatchService = sawBatchService;
    }

    @GetMapping
    public ApiResponse<PageResult<SawBatchView>> page(SawBatchQuery query) {
        return ApiResponse.success(sawBatchService.page(query));
    }

    @GetMapping("/by-order/{orderId}")
    public ApiResponse<List<SawBatchView>> byOrder(@PathVariable("orderId") Long orderId) {
        return ApiResponse.success(sawBatchService.listByOrder(orderId));
    }

    @GetMapping("/{id}")
    public ApiResponse<SawBatchView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(sawBatchService.detail(id));
    }

    /** 大板反查所属批次与历次更正（大板详情用）。 */
    @GetMapping("/lineage-by-slab")
    public ApiResponse<SlabLineageView> lineageBySlab(@RequestParam("slabId") Long slabId) {
        return ApiResponse.success(sawBatchService.lineageBySlab(slabId));
    }

    /** 开锯：建立批次并冻结荒料 / 工单 / 锯切方式 / 排板顺序。 */
    @PostMapping
    public ApiResponse<SawBatchView> create(@Valid @RequestBody BatchCreateReq req) {
        return ApiResponse.success("锯切批次已建立，排板顺序已冻结", sawBatchService.create(req));
    }

    /** 逐片登记产出（断网重试 / 重复扫码幂等；跨批次占用 409 回带当前归属）。 */
    @PostMapping("/{id}/outputs")
    public ApiResponse<SawBatchView> registerOutput(@PathVariable("id") Long id,
                                                    @Valid @RequestBody OutputRegisterReq req) {
        return ApiResponse.success("产出已逐片登记", sawBatchService.registerOutput(id, req));
    }

    /** 交接：班组完成登记，进入差异待核。 */
    @PostMapping("/{id}/handover")
    public ApiResponse<SawBatchView> handover(@PathVariable("id") Long id,
                                              @RequestBody(required = false) HandoverReq req) {
        return ApiResponse.success("交接完成，已比对计划与实产",
                sawBatchService.handover(id, req == null ? new HandoverReq() : req));
    }

    /** 差异逐项核清（确认实际 / 作废误登；必填原因）。 */
    @PostMapping("/diffs/{diffId}/resolve")
    public ApiResponse<SawBatchView> resolveDiff(@PathVariable("diffId") Long diffId,
                                                 @Valid @RequestBody DiffResolveReq req) {
        return ApiResponse.success("差异已核清", sawBatchService.resolveDiff(diffId, req));
    }

    /** 更正登记内容（实际尺寸 / 顺序 / 锯耗原因），保留前后值。 */
    @PostMapping("/outputs/{outputId}/correct")
    public ApiResponse<SawBatchView> correctOutput(@PathVariable("outputId") Long outputId,
                                                   @Valid @RequestBody OutputCorrectReq req) {
        return ApiResponse.success("登记内容已更正并留痕", sawBatchService.correctOutput(outputId, req));
    }

    /** 跨批次更正归属（保留先到记录，显式改派，不静默覆盖）。 */
    @PostMapping("/outputs/{outputId}/reassign")
    public ApiResponse<SawBatchView> reassignOutput(@PathVariable("outputId") Long outputId,
                                                    @Valid @RequestBody ReassignReq req) {
        return ApiResponse.success("产出已改派到目标批次，双方差异已重新比对",
                sawBatchService.reassignOutput(outputId, req));
    }

    /** 作废误登产出（被后续批次返切引用时禁止）。 */
    @PostMapping("/outputs/{outputId}/void")
    public ApiResponse<SawBatchView> voidOutput(@PathVariable("outputId") Long outputId,
                                                @Valid @RequestBody OutputVoidReq req) {
        return ApiResponse.success("误登产出已作废并留痕", sawBatchService.voidOutput(outputId, req));
    }

    /** 收口：差异全部核清才放行，单事务固化谱系并推进工单（并发 / 重复点击只有一个生效）。 */
    @PostMapping("/{id}/close")
    public ApiResponse<SawBatchView> close(@PathVariable("id") Long id,
                                           @RequestBody(required = false) BatchCloseReq req) {
        return ApiResponse.success("批次已收口，产出谱系固化，工单按合格实产推进",
                sawBatchService.close(id, req == null ? new BatchCloseReq() : req));
    }
}
