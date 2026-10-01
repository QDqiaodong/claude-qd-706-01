-- ============================================================================
-- 石材加工厂（大板加工）生产管理系统 · 建表 + 种子数据
-- 库名 stone_mill，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
--
-- 七个业务模块：
--   1. mill_block        荒料（矿口产地 / 石种 / 长宽高 / 体积 / 重量 / 采购单价 / 状态）
--   2. mill_slab         大板（所属荒料 / 长宽厚 / 面积 / 表面处理 / 质量等级 / 库位 / 状态）
--   3. mill_cutting_order 加工工单（锯切方式 / 计划板数 / 已完成板数 / 班组 / 状态）
--   4. mill_layout_item  排板方案（荒料上的 x/y/宽/高 + 顺序号 + 损耗率）
--   5. mill_delivery / mill_delivery_item 出库发货（客户 / 车牌 / 大板明细 / 金额 / 状态）
--   6. mill_quality_hold 大板质量隔离记录（缺陷 / 严重度 / 基础资料快照 / 受控流转状态）
--   7. mill_quality_event 质量隔离留痕（建立 / 送复检 / 历次复检意见 / 放行 / 报废，只追加）
--
-- 质量隔离口径：
--   * 同一块大板同一时刻只允许一条未收口（隔离中 / 待复检）记录，由部分唯一索引
--     uk_hold_active_slab（生成列 active_slab_id）在数据库层兜底并发重复发起；
--   * 隔离记录冻结当时的大板编号 / 荒料编号 / 等级 / 库位 / 状态快照，基础资料后续变化不改写；
--   * 复检结论只往 mill_quality_event 追加，不覆盖最初缺陷与历次意见；
--   * 隔离中 / 待复检即对发货派生冻结：大板不能再选入发货单，已在待装车单中的明细挂起禁运；
--     放行由活跃记录消失自动解除挂起，报废则从待装车单移除明细并重算总金额。
--
-- 派生口径：体积 = 长×宽×高/1e6；重量 = 体积×石种密度（大理石 2.7 / 花岗岩 2.8 /
-- 玉石 2.9 / 石英石 2.6 / 洞石 2.5）；面积 = 长×宽/1e4；明细金额 = 数量×面积×单价；
-- 损耗率 =（荒料排板面 - 已排板材面积）/ 排板面。
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS mill_quality_event;
DROP TABLE IF EXISTS mill_quality_hold;
DROP TABLE IF EXISTS mill_seq;
DROP TABLE IF EXISTS mill_delivery_item;
DROP TABLE IF EXISTS mill_delivery;
DROP TABLE IF EXISTS mill_layout_item;
DROP TABLE IF EXISTS mill_cutting_order;
DROP TABLE IF EXISTS mill_slab;
DROP TABLE IF EXISTS mill_block;

CREATE TABLE mill_block (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    block_code     VARCHAR(30)   NOT NULL COMMENT '荒料编号',
    quarry         VARCHAR(60)            COMMENT '矿口产地',
    stone_type     VARCHAR(20)            COMMENT '石种：MARBLE 大理石 / GRANITE 花岗岩 / JADE 玉石 / QUARTZ 石英石 / TRAVERTINE 洞石',
    length_cm      INT                    COMMENT '长（cm）',
    width_cm       INT                    COMMENT '宽（cm）',
    height_cm      INT                    COMMENT '高（cm）',
    volume_m3      DECIMAL(10,2)          COMMENT '体积（m³）= 长×宽×高/1e6',
    weight_ton     DECIMAL(10,2)          COMMENT '重量（吨）= 体积×石种密度',
    inbound_date   DATE                   COMMENT '进厂日期',
    purchase_price DECIMAL(10,2)          COMMENT '采购单价（元/m³）',
    supplier       VARCHAR(80)            COMMENT '供应商',
    status         VARCHAR(20)            COMMENT 'PENDING 待排产 / PROCESSING 加工中 / DONE 已完料 / ARCHIVED 结库',
    remark         VARCHAR(200)           COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_block_code (block_code),
    KEY idx_block_type (stone_type),
    KEY idx_block_status (status),
    KEY idx_block_inbound (inbound_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='荒料';

CREATE TABLE mill_slab (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    slab_code    VARCHAR(30)  NOT NULL COMMENT '大板编号',
    block_id     BIGINT                COMMENT '所属荒料',
    length_cm    INT                   COMMENT '长（cm）',
    width_cm     INT                   COMMENT '宽（cm）',
    thickness_cm DECIMAL(5,1)          COMMENT '厚度（cm）',
    area_m2      DECIMAL(10,2)         COMMENT '面积（m²）= 长×宽/1e4',
    finish       VARCHAR(20)           COMMENT '表面处理：POLISHED 光面 / HONED 亚光 / LITCHI 荔枝面 / FLAMED 火烧面 / LEATHER 皮革面',
    grade        VARCHAR(10)           COMMENT '质量等级：A / B / C / DEFECT 次品',
    location     VARCHAR(30)           COMMENT '库位',
    status       VARCHAR(20)           COMMENT 'INSTOCK 在库 / RESERVED 已预定 / OUTBOUND 已出库 / SCRAP 报废',
    remark       VARCHAR(200)          COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_slab_code (slab_code),
    KEY idx_slab_block (block_id),
    KEY idx_slab_grade (grade),
    KEY idx_slab_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='大板';

CREATE TABLE mill_cutting_order (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    order_no   VARCHAR(30) NOT NULL COMMENT '工单号',
    block_id   BIGINT               COMMENT '荒料',
    saw_type   VARCHAR(20)          COMMENT '锯切方式：FRAME 框架锯 / DISC 圆盘锯 / WIRE 绳锯 / COMBO 组合锯',
    plan_count INT                  COMMENT '计划板数',
    done_count INT                  COMMENT '已完成板数',
    start_date DATE                 COMMENT '开工日期',
    finish_date DATE                COMMENT '完工日期',
    team       VARCHAR(20)          COMMENT '班组',
    status     VARCHAR(20)          COMMENT 'PENDING 待排产 / PROCESSING 加工中 / DONE 已完工 / SETTLED 已结算',
    remark     VARCHAR(200)         COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_block (block_id),
    KEY idx_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='加工工单';

CREATE TABLE mill_layout_item (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    block_id   BIGINT                COMMENT '荒料',
    slab_id    BIGINT                COMMENT '对应大板',
    x_pos      INT                   COMMENT 'x 坐标（cm，沿荒料长度方向）',
    y_pos      INT                   COMMENT 'y 坐标（cm，沿荒料宽度方向）',
    item_width INT                   COMMENT '宽（cm）',
    item_height INT                  COMMENT '高（cm）',
    seq_no     INT                   COMMENT '锯切顺序号',
    waste_rate DECIMAL(6,2)          COMMENT '该荒料排布损耗率（%）',
    PRIMARY KEY (id),
    KEY idx_layout_block (block_id),
    KEY idx_layout_slab (slab_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='排板方案';

CREATE TABLE mill_delivery (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    delivery_no  VARCHAR(30)   NOT NULL COMMENT '发货单号',
    customer     VARCHAR(80)            COMMENT '客户',
    contact      VARCHAR(30)            COMMENT '联系人',
    phone        VARCHAR(20)            COMMENT '联系电话',
    plate_no     VARCHAR(20)            COMMENT '车牌',
    driver       VARCHAR(30)            COMMENT '司机',
    delivery_date DATE                  COMMENT '发货日期',
    total_amount DECIMAL(12,2)          COMMENT '总金额（元）',
    status       VARCHAR(20)            COMMENT 'LOADING 待装车 / SHIPPED 已发运 / SIGNED 已签收 / PAID 已结款',
    remark       VARCHAR(200)           COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_delivery_no (delivery_no),
    KEY idx_delivery_status (status),
    KEY idx_delivery_date (delivery_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库发货单';

CREATE TABLE mill_delivery_item (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    delivery_id BIGINT                COMMENT '发货单',
    slab_id    BIGINT                 COMMENT '大板',
    slab_code  VARCHAR(30)            COMMENT '大板编号快照',
    quantity   INT                    COMMENT '数量（片）',
    area_m2    DECIMAL(10,2)          COMMENT '面积（m²）',
    unit_price DECIMAL(10,2)          COMMENT '单价（元/m²）',
    amount     DECIMAL(12,2)          COMMENT '金额（元）= 数量×面积×单价',
    PRIMARY KEY (id),
    KEY idx_item_delivery (delivery_id),
    KEY idx_item_slab (slab_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发货大板明细';

-- 模块六：大板质量隔离记录（同一大板同一时刻只允许一条未收口记录）
CREATE TABLE mill_quality_hold (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    hold_no         VARCHAR(30)  NOT NULL COMMENT '隔离记录号，如 QH-2026-0001',
    slab_id         BIGINT       NOT NULL COMMENT '大板',
    round_no        INT          NOT NULL DEFAULT 1 COMMENT '第几轮隔离（放行后再发现问题新开一轮）',
    defect_type     VARCHAR(20)  NOT NULL COMMENT '缺陷类型：CHIPPED 崩边 / CRACK 裂纹 / COLOR_DIFF 色差 / HOLE 孔洞 / SCRATCH 划伤 / OTHER 其他',
    severity        VARCHAR(10)  NOT NULL COMMENT '严重程度：MINOR 轻微 / MAJOR 较重 / CRITICAL 严重',
    affect_position VARCHAR(100)          COMMENT '影响位置（如 左上角距边 10cm）',
    found_stage     VARCHAR(20)           COMMENT '发现环节：CUTTING 锯切 / GRINDING 打磨 / POLISHING 抛光 / INSPECTION 成检 / WAREHOUSE 入库 / LOADING 装车前 / FEEDBACK 客户反馈',
    site_note       VARCHAR(500)          COMMENT '现场说明',
    dispose_advice  VARCHAR(500)          COMMENT '处置建议',
    reporter        VARCHAR(30)           COMMENT '发现 / 登记人',
    status          VARCHAR(20)  NOT NULL COMMENT 'ISOLATED 隔离中 / PENDING_RECHECK 待复检 / RELEASED 已放行 / SCRAPPED 已报废',
    -- 建立时的基础资料快照（此后基础资料变化不改写历史证据）
    snap_slab_code  VARCHAR(30)  NOT NULL COMMENT '大板编号快照',
    snap_block_code VARCHAR(30)           COMMENT '所属荒料编号快照',
    snap_block_id   BIGINT                COMMENT '所属荒料 id 快照',
    snap_grade      VARCHAR(10)           COMMENT '质量等级快照',
    snap_location   VARCHAR(30)           COMMENT '库位快照',
    snap_slab_status VARCHAR(20)          COMMENT '大板状态快照',
    -- 放行 / 报废收口信息
    closed_remark   VARCHAR(500)          COMMENT '收口意见（放行结论 / 报废依据）',
    closed_by       VARCHAR(30)           COMMENT '收口操作人',
    closed_at       DATETIME              COMMENT '收口时间',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本（并发复检 / 重复点击只有一个生效）',
    created_at      DATETIME     NOT NULL COMMENT '建立时间',
    updated_at      DATETIME     NOT NULL COMMENT '最近流转时间',
    -- 生成列：记录未收口（隔离中 / 待复检）时等于 slab_id，收口后为 NULL。
    -- 配合唯一索引，在数据库层强制「同一大板同一时刻只有一条未收口记录」。
    active_slab_id  BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('ISOLATED','PENDING_RECHECK') THEN slab_id ELSE NULL END) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hold_no (hold_no),
    UNIQUE KEY uk_hold_active_slab (active_slab_id),
    KEY idx_hold_slab (slab_id),
    KEY idx_hold_status (status),
    KEY idx_hold_defect (defect_type),
    KEY idx_hold_severity (severity),
    KEY idx_hold_block (snap_block_code),
    KEY idx_hold_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='大板质量隔离记录';

-- 模块七：质量隔离流转留痕（只追加，不更新、不删除）
CREATE TABLE mill_quality_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    hold_id     BIGINT       NOT NULL COMMENT '质量隔离记录',
    event_type  VARCHAR(25)  NOT NULL COMMENT 'CREATE 建立 / SUBMIT_RECHECK 送复检 / RECHECK 复检意见 / RELEASE 放行 / SCRAP 报废',
    from_status VARCHAR(20)           COMMENT '流转前状态',
    to_status   VARCHAR(20)           COMMENT '流转后状态',
    conclusion  VARCHAR(20)           COMMENT '复检结论：PASS 合格放行 / REWORK 返工观察 / SCRAP 报废（仅复检类事件）',
    content     VARCHAR(500)          COMMENT '现场说明 / 复检意见 / 处置意见',
    operator    VARCHAR(30)           COMMENT '操作人',
    created_at  DATETIME     NOT NULL COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_event_hold (hold_id),
    KEY idx_event_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量隔离流转留痕';

-- 业务单据号原子序列（按 seq_key 行锁取号，杜绝并发同号）
CREATE TABLE mill_seq (
    seq_key   VARCHAR(40)  NOT NULL COMMENT '序列键，如 QH-2026',
    seq_value BIGINT       NOT NULL DEFAULT 0 COMMENT '当前已分配序号',
    PRIMARY KEY (seq_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单据号序列';

-- ---------------------------- 荒料 ----------------------------
INSERT INTO mill_block (id, block_code, quarry, stone_type, length_cm, width_cm, height_cm,
                        volume_m3, weight_ton, inbound_date, purchase_price, supplier, status, remark) VALUES
    (1, 'BLK-2026-001', '福建南安水头矿口', 'MARBLE', 360, 220, 170, 13.46, 36.34, '2026-06-02', 3800, '南安鑫磊矿业有限公司', 'DONE', '整料无裂，主切面已取大板'),
    (2, 'BLK-2026-002', '福建南安水头矿口', 'MARBLE', 340, 210, 165, 11.78, 31.81, '2026-06-08', 3650, '南安鑫磊矿业有限公司', 'PROCESSING', '侧面有轻微色线，需避让'),
    (3, 'BLK-2026-003', '山东五莲街头矿区', 'GRANITE', 320, 200, 160, 10.24, 28.67, '2026-06-15', 2900, '五莲石材集团股份有限公司', 'DONE', '五莲灰，颗粒均匀'),
    (4, 'BLK-2026-004', '山东五莲街头矿区', 'GRANITE', 330, 205, 170, 11.50, 32.20, '2026-06-21', 3050, '五莲石材集团股份有限公司', 'PROCESSING', '含一条暗裂纹，绳锯避让'),
    (5, 'BLK-2026-005', '河南南阳独山玉矿', 'JADE', 210, 140, 95, 2.79, 8.09, '2026-07-01', 12800, '南阳独山玉矿业贸易有限公司', 'ARCHIVED', '独山玉，俏色料'),
    (6, 'BLK-2026-006', '河南南阳独山玉矿', 'JADE', 200, 135, 90, 2.43, 7.05, '2026-07-05', 13600, '南阳独山玉矿业贸易有限公司', 'PROCESSING', '独山玉，色带偏灰'),
    (7, 'BLK-2026-007', '广东云浮思劳矿区', 'QUARTZ', 300, 170, 150, 7.65, 19.89, '2026-07-12', 2200, '云浮科迪石英石有限公司', 'DONE', '石英石大料，锯路稳定'),
    (8, 'BLK-2026-008', '广东云浮思劳矿区', 'QUARTZ', 290, 160, 145, 6.73, 17.50, '2026-07-18', 2350, '云浮科迪石英石有限公司', 'PROCESSING', '边角有缺角，先修边'),
    (9, 'BLK-2026-009', '意大利卡拉拉（进口）', 'TRAVERTINE', 270, 160, 140, 6.05, 15.13, '2026-07-25', 6800, '厦门海石进出口有限公司', 'PENDING', '进口洞石，孔洞偏大'),
    (10, 'BLK-2026-010', '意大利卡拉拉（进口）', 'TRAVERTINE', 265, 155, 135, 5.55, 13.88, '2026-08-01', 7100, '厦门海石进出口有限公司', 'PENDING', '进口洞石，需补胶'),
    (11, 'BLK-2026-011', '福建南安水头矿口', 'MARBLE', 370, 225, 175, 14.57, 39.34, '2026-08-08', 3980, '南安鑫磊矿业有限公司', 'PENDING', '超大料，待框架锯排期'),
    (12, 'BLK-2026-012', '山东五莲街头矿区', 'GRANITE', 310, 195, 155, 9.37, 26.24, '2026-08-15', 3120, '五莲石材集团股份有限公司', 'PENDING', '五莲红，色差控制严格');

-- ---------------------------- 大板 ----------------------------
INSERT INTO mill_slab (id, slab_code, block_id, length_cm, width_cm, thickness_cm, area_m2,
                       finish, grade, location, status, remark) VALUES
    (1, 'SLB-0001', 1, 175, 105, 2.0, 1.84, 'POLISHED', 'A', 'A-01-01', 'OUTBOUND', ''),
    (2, 'SLB-0002', 1, 170, 105, 2.0, 1.79, 'HONED', 'A', 'A-01-02', 'OUTBOUND', ''),
    (3, 'SLB-0003', 1, 115, 105, 2.0, 1.21, 'POLISHED', 'B', 'A-01-03', 'RESERVED', ''),
    (4, 'SLB-0004', 1, 118, 100, 2.0, 1.18, 'POLISHED', 'A', 'A-01-04', 'RESERVED', ''),
    (5, 'SLB-0005', 1, 112, 100, 1.8, 1.12, 'HONED', 'C', 'A-01-05', 'INSTOCK', ''),
    (6, 'SLB-0006', 2, 165, 100, 2.0, 1.65, 'POLISHED', 'A', 'A-02-01', 'OUTBOUND', ''),
    (7, 'SLB-0007', 2, 160, 100, 2.0, 1.60, 'HONED', 'B', 'A-02-02', 'OUTBOUND', ''),
    (8, 'SLB-0008', 2, 110, 100, 2.0, 1.10, 'POLISHED', 'C', 'A-02-03', 'INSTOCK', ''),
    (9, 'SLB-0009', 2, 105, 95, 1.8, 1.00, 'HONED', 'A', 'A-02-04', 'INSTOCK', ''),
    (10, 'SLB-0010', 2, 100, 95, 2.0, 0.95, 'POLISHED', 'DEFECT', 'A-02-05', 'SCRAP', '边部崩边超 3cm，判次品'),
    (11, 'SLB-0011', 3, 155, 95, 3.0, 1.47, 'FLAMED', 'A', 'B-01-01', 'OUTBOUND', ''),
    (12, 'SLB-0012', 3, 150, 95, 3.0, 1.43, 'FLAMED', 'B', 'B-01-02', 'OUTBOUND', ''),
    (13, 'SLB-0013', 3, 150, 90, 3.0, 1.35, 'LITCHI', 'A', 'B-01-03', 'RESERVED', ''),
    (14, 'SLB-0014', 3, 140, 90, 3.0, 1.26, 'LEATHER', 'C', 'B-01-04', 'RESERVED', ''),
    (15, 'SLB-0015', 4, 160, 100, 3.0, 1.60, 'FLAMED', 'A', 'B-02-01', 'OUTBOUND', ''),
    (16, 'SLB-0016', 4, 155, 100, 3.0, 1.55, 'POLISHED', 'A', 'B-02-02', 'OUTBOUND', ''),
    (17, 'SLB-0017', 4, 150, 95, 3.0, 1.43, 'LEATHER', 'B', 'B-02-03', 'INSTOCK', ''),
    (18, 'SLB-0018', 4, 145, 95, 3.0, 1.38, 'LITCHI', 'B', 'B-02-04', 'INSTOCK', ''),
    (19, 'SLB-0019', 5, 100, 70, 1.8, 0.70, 'POLISHED', 'A', 'C-01-01', 'OUTBOUND', ''),
    (20, 'SLB-0020', 5, 95, 70, 1.8, 0.67, 'POLISHED', 'A', 'C-01-02', 'SCRAP', '客户反馈贯穿裂，质量判废（发货单 DEL-2026-0608 仍已签收不倒改）'),
    (21, 'SLB-0021', 5, 90, 65, 1.8, 0.59, 'POLISHED', 'B', 'C-01-03', 'OUTBOUND', ''),
    (22, 'SLB-0022', 5, 85, 65, 1.8, 0.55, 'HONED', 'C', 'C-01-04', 'OUTBOUND', ''),
    (23, 'SLB-0023', 6, 95, 65, 1.8, 0.62, 'POLISHED', 'A', 'C-02-01', 'INSTOCK', ''),
    (24, 'SLB-0024', 6, 90, 60, 1.8, 0.54, 'POLISHED', 'B', 'C-02-02', 'INSTOCK', ''),
    (25, 'SLB-0025', 6, 90, 65, 1.8, 0.59, 'HONED', 'C', 'C-02-03', 'INSTOCK', ''),
    (26, 'SLB-0026', 6, 85, 60, 1.8, 0.51, 'POLISHED', 'DEFECT', 'C-02-04', 'SCRAP', '底色发黄，判次品'),
    (27, 'SLB-0027', 7, 145, 85, 2.0, 1.23, 'POLISHED', 'A', 'D-01-01', 'OUTBOUND', ''),
    (28, 'SLB-0028', 7, 140, 85, 2.0, 1.19, 'LEATHER', 'B', 'D-01-02', 'OUTBOUND', ''),
    (29, 'SLB-0029', 7, 135, 80, 2.0, 1.08, 'POLISHED', 'A', 'D-01-03', 'RESERVED', ''),
    (30, 'SLB-0030', 7, 130, 80, 2.0, 1.04, 'LEATHER', 'B', 'D-01-04', 'RESERVED', ''),
    (31, 'SLB-0031', 8, 140, 80, 2.0, 1.12, 'POLISHED', 'A', 'D-02-01', 'OUTBOUND', ''),
    (32, 'SLB-0032', 8, 135, 80, 2.0, 1.08, 'POLISHED', 'B', 'D-02-02', 'OUTBOUND', ''),
    (33, 'SLB-0033', 8, 130, 75, 2.0, 0.98, 'LEATHER', 'C', 'D-02-03', 'RESERVED', ''),
    (34, 'SLB-0034', 8, 125, 75, 2.0, 0.94, 'LEATHER', 'B', 'D-02-04', 'RESERVED', '');

-- ---------------------------- 加工工单 ----------------------------
INSERT INTO mill_cutting_order (id, order_no, block_id, saw_type, plan_count, done_count,
                                start_date, finish_date, team, status, remark) VALUES
    (1, 'CO-2026-0001', 1, 'FRAME', 5, 5, '2026-06-03', '2026-06-09', '甲班', 'DONE', ''),
    (2, 'CO-2026-0002', 2, 'FRAME', 5, 3, '2026-06-10', NULL, '甲班', 'PROCESSING', '锯条已更换一次，进度偏慢'),
    (3, 'CO-2026-0003', 3, 'DISC', 4, 4, '2026-06-16', '2026-06-22', '乙班', 'DONE', ''),
    (4, 'CO-2026-0004', 4, 'WIRE', 4, 2, '2026-06-23', NULL, '乙班', 'PROCESSING', '暗裂纹避让，实际板数待定'),
    (5, 'CO-2026-0005', 5, 'WIRE', 4, 4, '2026-07-02', '2026-07-06', '丙班', 'SETTLED', ''),
    (6, 'CO-2026-0006', 6, 'COMBO', 4, 1, '2026-07-07', NULL, '丙班', 'PROCESSING', '玉石料慢切，单片 6 小时'),
    (7, 'CO-2026-0007', 7, 'FRAME', 4, 4, '2026-07-13', '2026-07-19', '甲班', 'DONE', ''),
    (8, 'CO-2026-0008', 8, 'DISC', 4, 3, '2026-07-20', NULL, '乙班', 'PROCESSING', '圆盘锯吃刀量调小'),
    (9, 'CO-2026-0009', 9, 'COMBO', 6, 0, NULL, NULL, '丙班', 'PENDING', '待荒料 009 到厂后开工'),
    (10, 'CO-2026-0010', 10, 'WIRE', 6, 0, NULL, NULL, '甲班', 'PENDING', '待荒料 010 到厂后开工'),
    (11, 'CO-2026-0011', 11, 'FRAME', 8, 0, NULL, NULL, '乙班', 'PENDING', '超大料，需两班倒'),
    (12, 'CO-2026-0012', 12, 'DISC', 5, 0, NULL, NULL, '丙班', 'PENDING', '色差控制，单片留样');

-- ---------------------------- 排板方案 ----------------------------
INSERT INTO mill_layout_item (id, block_id, slab_id, x_pos, y_pos, item_width, item_height, seq_no, waste_rate) VALUES
    (1, 1, 1, 0, 0, 175, 105, 1, 9.97),
    (2, 1, 2, 175, 0, 170, 105, 2, 9.97),
    (3, 1, 3, 0, 105, 115, 105, 3, 9.97),
    (4, 1, 4, 115, 105, 118, 100, 4, 9.97),
    (5, 1, 5, 233, 105, 112, 100, 5, 9.97),
    (6, 2, 6, 0, 0, 165, 100, 1, 11.80),
    (7, 2, 7, 165, 0, 160, 100, 2, 11.80),
    (8, 2, 8, 0, 100, 110, 100, 3, 11.80),
    (9, 2, 9, 110, 100, 105, 95, 4, 11.80),
    (10, 2, 10, 215, 100, 100, 95, 5, 11.80),
    (11, 3, 11, 0, 0, 155, 95, 1, 13.95),
    (12, 3, 12, 155, 0, 150, 95, 2, 13.95),
    (13, 3, 13, 0, 95, 150, 90, 3, 13.95),
    (14, 3, 14, 150, 95, 140, 90, 4, 13.95),
    (15, 4, 15, 0, 0, 160, 100, 1, 12.01),
    (16, 4, 16, 160, 0, 155, 100, 2, 12.01),
    (17, 4, 17, 0, 100, 150, 95, 3, 12.01),
    (18, 4, 18, 150, 100, 145, 95, 4, 12.01),
    (19, 5, 19, 0, 0, 100, 70, 1, 14.88),
    (20, 5, 20, 100, 0, 95, 70, 2, 14.88),
    (21, 5, 21, 0, 70, 90, 65, 3, 14.88),
    (22, 5, 22, 90, 70, 85, 65, 4, 14.88),
    (23, 6, 23, 0, 0, 95, 65, 1, 16.57),
    (24, 6, 25, 95, 0, 90, 65, 2, 16.57),
    (25, 6, 24, 0, 65, 90, 60, 3, 16.57),
    (26, 6, 26, 90, 65, 85, 60, 4, 16.57),
    (27, 7, 27, 0, 0, 145, 85, 1, 10.93),
    (28, 7, 28, 145, 0, 140, 85, 2, 10.93),
    (29, 7, 29, 0, 85, 135, 80, 3, 10.93),
    (30, 7, 30, 135, 85, 130, 80, 4, 10.93),
    (31, 8, 31, 0, 0, 140, 80, 1, 11.37),
    (32, 8, 32, 140, 0, 135, 80, 2, 11.37),
    (33, 8, 33, 0, 80, 130, 75, 3, 11.37),
    (34, 8, 34, 130, 80, 125, 75, 4, 11.37);

-- ---------------------------- 出库发货单 ----------------------------
INSERT INTO mill_delivery (id, delivery_no, customer, contact, phone, plate_no, driver,
                           delivery_date, total_amount, status, remark) VALUES
    (1, 'DEL-2026-0601', '广州华诚装饰工程有限公司', '陈志明', '13802241188', '粤A·8H52L', '刘建国', '2026-07-06', 1742.40, 'PAID', '首单，含装卸费'),
    (2, 'DEL-2026-0602', '深圳金地石材有限公司', '李文彬', '13902997766', '粤B·5K19T', '王海涛', '2026-07-12', 1690.00, 'SIGNED', ''),
    (3, 'DEL-2026-0603', '东莞宏远建材有限公司', '周敏', '13712635520', '粤S·2Q73M', '陈水生', '2026-07-18', 1102.00, 'SIGNED', ''),
    (4, 'DEL-2026-0604', '厦门海沧建设集团', '吴国荣', '13606018842', '闽D·6L08K', '林志强', '2026-07-24', 1354.50, 'SHIPPED', '海运拼柜，需木箱加固'),
    (5, 'DEL-2026-0605', '佛山顺德精工装饰', '梁晓峰', '13509283371', '粤E·9P41N', '黄伟明', '2026-07-28', 1476.20, 'SHIPPED', ''),
    (6, 'DEL-2026-0606', '中山小榄石材商行', '冯少华', '13420297755', '粤T·3R62F', '张永昌', '2026-08-02', 1276.00, 'SHIPPED', ''),
    (7, 'DEL-2026-0607', '郑州玉雕工艺厂', '赵玉山', '13703812299', '豫A·7D25J', '孙铁柱', '2026-08-06', 4000.00, 'SIGNED', '玉石件，单独木架包装'),
    (8, 'DEL-2026-0608', '南阳宝玉文化传媒', '王雪梅', '13903776618', '豫R·1F84B', '马长顺', '2026-08-09', 4032.00, 'SIGNED', ''),
    (9, 'DEL-2026-0609', '珠海横琴置业工程部', '郑家豪', '13509812233', '粤C·4M19P', '何俊杰', '2026-08-13', 1147.20, 'LOADING', '待装车，客户自提'),
    (10, 'DEL-2026-0610', '惠州大亚湾装饰工程', '罗建华', '13602745581', '粤L·8V37D', '曾德胜', '2026-08-16', 991.80, 'LOADING', ''),
    (11, 'DEL-2026-0611', '汕头龙湖石材市场', '许泽凯', '13802339907', '粤D·5W62H', '姚楚明', '2026-08-19', 1293.20, 'LOADING', ''),
    (12, 'DEL-2026-0612', '江门蓬江建材中心', '甄惠玲', '13702287764', '粤J·2Y95G', '梁根发', '2026-08-22', 1113.60, 'LOADING', '尾单，装车前再复核');

-- ---------------------------- 发货大板明细 ----------------------------
INSERT INTO mill_delivery_item (id, delivery_id, slab_id, slab_code, quantity, area_m2, unit_price, amount) VALUES
    (1, 1, 1, 'SLB-0001', 1, 1.84, 480, 883.20),
    (2, 1, 2, 'SLB-0002', 1, 1.79, 480, 859.20),
    (3, 2, 6, 'SLB-0006', 1, 1.65, 520, 858.00),
    (4, 2, 7, 'SLB-0007', 1, 1.60, 520, 832.00),
    (5, 3, 11, 'SLB-0011', 1, 1.47, 380, 558.60),
    (6, 3, 12, 'SLB-0012', 1, 1.43, 380, 543.40),
    (7, 4, 15, 'SLB-0015', 1, 1.60, 430, 688.00),
    (8, 4, 16, 'SLB-0016', 1, 1.55, 430, 666.50),
    (9, 5, 27, 'SLB-0027', 1, 1.23, 610, 750.30),
    (10, 5, 28, 'SLB-0028', 1, 1.19, 610, 725.90),
    (11, 6, 31, 'SLB-0031', 1, 1.12, 580, 649.60),
    (12, 6, 32, 'SLB-0032', 1, 1.08, 580, 626.40),
    (13, 7, 19, 'SLB-0019', 1, 0.70, 3200, 2240.00),
    (14, 7, 22, 'SLB-0022', 1, 0.55, 3200, 1760.00),
    (15, 8, 20, 'SLB-0020', 1, 0.67, 3200, 2144.00),
    (16, 8, 21, 'SLB-0021', 1, 0.59, 3200, 1888.00),
    (17, 9, 3, 'SLB-0003', 1, 1.21, 480, 580.80),
    (18, 9, 4, 'SLB-0004', 1, 1.18, 480, 566.40),
    (19, 10, 13, 'SLB-0013', 1, 1.35, 380, 513.00),
    (20, 10, 14, 'SLB-0014', 1, 1.26, 380, 478.80),
    (21, 11, 29, 'SLB-0029', 1, 1.08, 610, 658.80),
    (22, 11, 30, 'SLB-0030', 1, 1.04, 610, 634.40),
    (23, 12, 33, 'SLB-0033', 1, 0.98, 580, 568.40),
    (24, 12, 34, 'SLB-0034', 1, 0.94, 580, 545.20);

-- ---------------------------- 质量隔离记录（含历史轮次与留痕） ----------------------------
-- 1) SLB-0005 隔离中（在库，未挂发货单）
INSERT INTO mill_quality_hold (id, hold_no, slab_id, round_no, defect_type, severity, affect_position,
                               found_stage, site_note, dispose_advice, reporter, status,
                               snap_slab_code, snap_block_code, snap_block_id, snap_grade, snap_location,
                               snap_slab_status, closed_remark, closed_by, closed_at, version, created_at, updated_at) VALUES
    (1, 'QH-2026-0001', 5, 1, 'CHIPPED', 'MINOR', '右上角，崩口约 1.5cm',
     'POLISHING', '抛光下板时发现右上角小崩口，疑似吸盘偏压', '先隔离，复检确认能否修磨后按 B 级出', '质检-周工', 'ISOLATED',
     'SLB-0005', 'BLK-2026-001', 1, 'C', 'A-01-05', 'INSTOCK', NULL, NULL, NULL, 0, '2026-09-26 09:20:00', '2026-09-26 09:20:00'),
-- 2) SLB-0029 待复检（已预定，挂在待装车发货单 11，整单质量挂起禁运）
    (2, 'QH-2026-0002', 29, 1, 'CRACK', 'MAJOR', '板面中部横向细裂纹约 18cm',
     'LOADING', '装车前对光复检发现中部暗裂，客户订单对板面要求高', '暂停发运，复检判定放行或报废；若报废需从 DEL-2026-0611 移除', '仓库-林班长', 'PENDING_RECHECK',
     'SLB-0029', 'BLK-2026-007', 7, 'A', 'D-01-03', 'RESERVED', NULL, NULL, NULL, 1, '2026-09-27 14:05:00', '2026-09-28 08:40:00'),
-- 3) SLB-0033 待复检（已预定，挂在待装车发货单 12）
    (3, 'QH-2026-0003', 33, 1, 'COLOR_DIFF', 'MINOR', '左半边色带偏深，与同批板色差 ΔE 偏大',
     'WAREHOUSE', '客户指定同批无色差，库位复核发现与 SLB-0034 放在一起观感不一致', '送质检复检，必要时换板', '仓库-林班长', 'PENDING_RECHECK',
     'SLB-0033', 'BLK-2026-008', 8, 'C', 'D-02-03', 'RESERVED', NULL, NULL, NULL, 1, '2026-09-28 10:30:00', '2026-09-29 16:10:00'),
-- 4) SLB-0011 已放行（发运后补录，历史发货状态不倒改；演示前一轮保留）
    (4, 'QH-2026-0004', 11, 1, 'SCRATCH', 'MINOR', '表面浅划伤 2 条，约 3~5cm',
     'INSPECTION', '成检发现浅划伤，抛光可去除', '返抛后复检', '质检-周工', 'RELEASED',
     'SLB-0011', 'BLK-2026-003', 3, 'A', 'B-01-01', 'OUTBOUND', '返抛后划伤消失，板面合格，同意放行', '质检-高主管', '2026-07-10 11:00:00', 1, '2026-07-08 15:00:00', '2026-07-10 11:00:00'),
-- 5) SLB-0020 已报废（已发运签收后补录，仅质量收口，不倒改发货与大板出库历史）
    (5, 'QH-2026-0005', 20, 1, 'CRACK', 'CRITICAL', '对角线贯穿裂，已贴红色禁用标识',
     'FEEDBACK', '客户现场开箱反馈沿对角贯穿裂，照片回传，玉石件不可修复', '已发运签收，只做质量报废收口；理赔另行处理，不倒改发货历史', '质检-高主管', 'SCRAPPED',
     'SLB-0020', 'BLK-2026-005', 5, 'A', 'C-01-02', 'OUTBOUND', '贯穿性裂纹不可修复，判定报废，禁止再次流转', '质检-高主管', '2026-08-15 13:30:00', 1, '2026-08-15 10:00:00', '2026-08-15 13:30:00');

INSERT INTO mill_quality_event (id, hold_id, event_type, from_status, to_status, conclusion, content, operator, created_at) VALUES
    (1, 1, 'CREATE', NULL, 'ISOLATED', NULL, '抛光下板时发现右上角小崩口，疑似吸盘偏压。处置建议：先隔离，复检确认能否修磨后按 B 级出。', '质检-周工', '2026-09-26 09:20:00'),
    (2, 2, 'CREATE', NULL, 'ISOLATED', NULL, '装车前对光复检发现板面中部横向细裂纹约 18cm，暂停装车。', '仓库-林班长', '2026-09-27 14:05:00'),
    (3, 2, 'SUBMIT_RECHECK', 'ISOLATED', 'PENDING_RECHECK', NULL, '裂纹走向平直，需质检对光并做渗透复检，判定能否放行。', '仓库-林班长', '2026-09-28 08:40:00'),
    (4, 3, 'CREATE', NULL, 'ISOLATED', NULL, '库位复核发现 SLB-0033 左半边色带偏深，与同批 SLB-0034 观感不一致。', '仓库-林班长', '2026-09-28 10:30:00'),
    (5, 3, 'SUBMIT_RECHECK', 'ISOLATED', 'PENDING_RECHECK', NULL, '客户指定同批无色差，送质检按 ΔE 标准复检。', '仓库-林班长', '2026-09-29 16:10:00'),
    (6, 4, 'CREATE', NULL, 'ISOLATED', NULL, '成检发现表面浅划伤 2 条约 3~5cm，抛光可去除。', '质检-周工', '2026-07-08 15:00:00'),
    (7, 4, 'SUBMIT_RECHECK', 'ISOLATED', 'PENDING_RECHECK', NULL, '安排返抛，完成后复检。', '质检-周工', '2026-07-09 09:00:00'),
    (8, 4, 'RECHECK', 'PENDING_RECHECK', 'PENDING_RECHECK', 'REWORK', '返抛一遍后仍有轻微痕迹，再抛一遍。', '质检-高主管', '2026-07-10 09:30:00'),
    (9, 4, 'RELEASE', 'PENDING_RECHECK', 'RELEASED', 'PASS', '返抛后划伤消失，板面合格，同意放行。', '质检-高主管', '2026-07-10 11:00:00'),
    (10, 5, 'CREATE', NULL, 'ISOLATED', NULL, '客户现场开箱反馈沿对角贯穿裂，照片回传。', '质检-高主管', '2026-08-15 10:00:00'),
    (11, 5, 'SUBMIT_RECHECK', 'ISOLATED', 'PENDING_RECHECK', NULL, '玉石件，转资深质检评定可修复性。', '质检-高主管', '2026-08-15 10:20:00'),
    (12, 5, 'SCRAP', 'PENDING_RECHECK', 'SCRAPPED', 'SCRAP', '贯穿性裂纹不可修复，判定报废，禁止再次流转；已发运签收，发货历史不倒改。', '质检-高主管', '2026-08-15 13:30:00');

-- 隔离记录号序列：种子已占用 QH-2026-0001~0005
INSERT INTO mill_seq (seq_key, seq_value) VALUES ('QH-2026', 5);

SET FOREIGN_KEY_CHECKS = 1;
