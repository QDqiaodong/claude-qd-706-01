package com.stone.mill.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/** 出参视图：实体 + 字典中文名 + 汇总数字，全部在这里定义。 */
public final class Views {

    private Views() {
    }

    /** 手写 Criteria 分页的统一出参（countQuery 填 total，结果查询填 rows）。 */
    @Data
    public static class PageResult<T> {

        private List<T> rows = new ArrayList<>();
        private long total;
        private int page;
        private int size;

        public PageResult() {
        }

        public PageResult(List<T> rows, long total, int page, int size) {
            this.rows = rows;
            this.total = total;
            this.page = page;
            this.size = size;
        }
    }

    // ---------------- 荒料 ----------------

    @Data
    public static class BlockView {

        private Long id;
        private String blockCode;
        private String quarry;
        private String stoneType;
        private String stoneTypeName;
        private Integer lengthCm;
        private Integer widthCm;
        private Integer heightCm;
        private BigDecimal volumeM3;
        private BigDecimal weightTon;
        private LocalDate inboundDate;
        private BigDecimal purchasePrice;
        private BigDecimal purchaseAmount;
        private String supplier;
        private String status;
        private String statusName;
        private String remark;
        /** 该荒料已出大板数 */
        private long slabCount;
        /** 该荒料已出大板总面积（m²） */
        private BigDecimal slabArea;
    }

    @Data
    public static class BlockSummary {

        private long blockCount;
        private BigDecimal totalVolume;
        private BigDecimal totalWeight;
        private BigDecimal purchaseAmount;
        private long pendingCount;
        private long processingCount;
        private long doneCount;
        private long archivedCount;
    }

    // ---------------- 大板 ----------------

    @Data
    public static class SlabView {

        private Long id;
        private String slabCode;
        private Long blockId;
        private String blockCode;
        private String stoneType;
        private String stoneTypeName;
        private Integer lengthCm;
        private Integer widthCm;
        private BigDecimal thicknessCm;
        private BigDecimal areaM2;
        private String finish;
        private String finishName;
        private String grade;
        private String gradeName;
        private String location;
        private String status;
        private String statusName;
        private String remark;
        /** 质量隔离：是否存在未收口（隔离中 / 待复检）记录，为 true 时发货冻结 */
        private boolean qualityHold;
        /** 当前未收口隔离记录号（无则 null） */
        private String qualityHoldNo;
        /** 当前隔离阶段状态：ISOLATED / PENDING_RECHECK / null */
        private String qualityHoldStatus;
        private String qualityHoldStatusName;
        /** 当前隔离主要缺陷（中文名） */
        private String qualityDefectName;
    }

    @Data
    public static class SlabSummary {

        private long slabCount;
        private BigDecimal totalArea;
        private BigDecimal instockArea;
        private long instockCount;
        private long reservedCount;
        private long outboundCount;
        private long scrapCount;
        private long gradeA;
        private long gradeB;
        private long gradeC;
        private long gradeDefect;
    }

    // ---------------- 加工工单 ----------------

    @Data
    public static class CuttingOrderView {

        private Long id;
        private String orderNo;
        private Long blockId;
        private String blockCode;
        private String stoneType;
        private String stoneTypeName;
        private String sawType;
        private String sawTypeName;
        private Integer planCount;
        private Integer doneCount;
        /** 完成进度（0~100） */
        private int progress;
        private LocalDate startDate;
        private LocalDate finishDate;
        private String team;
        private String status;
        private String statusName;
        private String remark;
    }

    // ---------------- 排板方案 ----------------

    @Data
    public static class LayoutItemView {

        private Long id;
        private Long slabId;
        private String slabCode;
        @JsonProperty("xPos")
        private Integer xPos;

        @JsonProperty("yPos")
        private Integer yPos;
        private Integer width;
        private Integer height;
        private Integer seqNo;
        private String grade;
        private String gradeName;
        private String finishName;
        private BigDecimal areaM2;
        private String status;
        private String statusName;
        /** 前端 SVG 着色用 */
        private String color;
    }

    @Data
    public static class LayoutPlan {

        private Long blockId;
        private String blockCode;
        private String stoneType;
        private String stoneTypeName;
        private Integer lengthCm;
        private Integer widthCm;
        /** 排板面面积（m²） */
        private BigDecimal faceArea;
        /** 已排板材面积（m²） */
        private BigDecimal placedArea;
        /** 剩余留白面积（m²） */
        private BigDecimal remainArea;
        private BigDecimal wasteRate;
        private int placedCount;
        private int slabCount;
        private List<LayoutItemView> items = new ArrayList<>();
    }

    // ---------------- 出库发货 ----------------

    @Data
    public static class DeliveryView {

        private Long id;
        private String deliveryNo;
        private String customer;
        private String contact;
        private String phone;
        private String plateNo;
        private String driver;
        private LocalDate deliveryDate;
        private BigDecimal totalAmount;
        private String status;
        private String statusName;
        private String remark;
        private int itemCount;
        private int totalQuantity;
        private BigDecimal totalArea;
        /** 质量挂起：待装车单上存在隔离中 / 待复检的大板；为 true 时禁止发运，明细与金额原样保留 */
        private boolean qualityBlocked;
        /** 被挂起的明细数 */
        private int blockedItemCount;
        /** 被挂起的大板编号，供列表一眼看出阻断来源 */
        private List<String> blockedSlabCodes = new ArrayList<>();
        /** 挂起涉及的隔离记录号 */
        private List<String> blockedHoldNos = new ArrayList<>();
    }

    @Data
    public static class DeliveryItemView {

        private Long id;
        private Long deliveryId;
        private Long slabId;
        private String slabCode;
        private Integer quantity;
        private BigDecimal areaM2;
        private BigDecimal unitPrice;
        private BigDecimal amount;
        private String grade;
        private String gradeName;
        private String finishName;
        private Integer lengthCm;
        private Integer widthCm;
        private String location;
        /** 该明细大板当前是否处于质量隔离（隔离中 / 待复检） */
        private boolean qualityBlocked;
        private String qualityHoldNo;
        private String qualityHoldStatusName;
        private String qualityDefectName;
    }

    @Data
    public static class DeliverySummary {

        private long deliveryCount;
        private BigDecimal totalAmount;
        private BigDecimal totalArea;
        private long loadingCount;
        private long shippedCount;
        private long signedCount;
        private long paidCount;
        /** 被质量挂起禁运的待装车发货单数 */
        private long blockedCount;
    }

    // ---------------- 质量隔离 ----------------

    @Data
    public static class QualityEventView {

        private Long id;
        private Long holdId;
        private String eventType;
        private String eventTypeName;
        private String fromStatus;
        private String fromStatusName;
        private String toStatus;
        private String toStatusName;
        private String conclusion;
        private String conclusionName;
        private String content;
        private String operator;
        private LocalDateTime createdAt;
    }

    /** 质量隔离记录详情 / 列表行：快照字段 + 当前阻断的发货单 + 留痕时间线 */
    @Data
    public static class QualityHoldView {

        private Long id;
        private String holdNo;
        private Long slabId;
        private Integer roundNo;

        // 缺陷登记
        private String defectType;
        private String defectTypeName;
        private String severity;
        private String severityName;
        private String affectPosition;
        private String foundStage;
        private String foundStageName;
        private String siteNote;
        private String disposeAdvice;
        private String reporter;

        // 受控流转
        private String status;
        private String statusName;
        private boolean active;
        private Integer version;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String closedRemark;
        private String closedBy;
        private LocalDateTime closedAt;

        // 建立时的基础资料快照（历史证据，后续资料变化不改写）
        private String snapSlabCode;
        private String snapBlockCode;
        private Long snapBlockId;
        private String snapGrade;
        private String snapGradeName;
        private String snapLocation;
        private String snapSlabStatus;
        private String snapSlabStatusName;

        // 大板当前资料（与快照对照，能看出资料已变更但证据不动）
        private String currentSlabCode;
        private String currentGrade;
        private String currentGradeName;
        private String currentLocation;
        private String currentSlabStatus;
        private String currentSlabStatusName;
        private boolean slabScrapped;

        /** 当前被该隔离阻断的待装车发货单（放行后为空；报废后明细已移除） */
        private List<BlockedDeliveryView> blockedDeliveries = new ArrayList<>();

        /** 同一块大板的历轮记录（前序已放行 / 已报废轮次） */
        private List<QualityRoundView> rounds = new ArrayList<>();

        /** 时间线：建立 → 送复检 → 历次复检 → 放行 / 报废 */
        private List<QualityEventView> events = new ArrayList<>();
    }

    @Data
    public static class BlockedDeliveryView {

        private Long deliveryId;
        private String deliveryNo;
        private String customer;
        private String status;
        private String statusName;
        private BigDecimal itemAmount;
    }

    @Data
    public static class QualityRoundView {

        private Long id;
        private String holdNo;
        private Integer roundNo;
        private String status;
        private String statusName;
        private String defectTypeName;
        private String severityName;
        private LocalDateTime createdAt;
        private LocalDateTime closedAt;
    }

    @Data
    public static class QualityHoldSummary {

        private long holdCount;
        private long isolatedCount;
        private long pendingRecheckCount;
        private long releasedCount;
        private long scrappedCount;
        /** 当前被质量挂起禁运的待装车发货单数 */
        private long blockedDeliveryCount;
    }

    // ---------------- 首页总览 ----------------

    @Data
    public static class Overview {

        private long blockCount;
        private BigDecimal blockVolume;
        private BigDecimal blockWeight;
        private long processingBlock;
        private long pendingBlock;
        private long slabCount;
        private BigDecimal slabArea;
        private BigDecimal instockArea;
        private long instockSlab;
        private long reservedSlab;
        private long orderProcessing;
        private long orderPending;
        private BigDecimal avgWasteRate;
        private long deliveryCount;
        private BigDecimal deliveryAmount;
    }

    // ---------------- 左侧树 ----------------

    @Data
    public static class TreeNode {

        private String key;
        private String label;
        /** block / slab */
        private String type;
        private Long id;
        private Long blockId;
        private String stoneType;
        private String grade;
        private BigDecimal areaM2;
        private List<TreeNode> children = new ArrayList<>();
    }
}
