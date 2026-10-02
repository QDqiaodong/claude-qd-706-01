package com.stone.mill.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.stone.mill.dto.Queries.SawBatchQuery;
import com.stone.mill.entity.SawBatch;
import com.stone.mill.entity.SawChange;
import com.stone.mill.entity.SawDiff;
import com.stone.mill.entity.SawEvent;
import com.stone.mill.entity.SawOutput;
import com.stone.mill.entity.SawPlan;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * 模块八：锯切批次谱系仓储（批次 / 冻结排板 / 逐片产出 / 差异 / 更正 / 留痕六张表）。
 *
 * <p>并发正确性由数据库约束兜底，不靠先查后插：
 * <ul>
 *   <li>{@code lockForUpdate}：收口时 SELECT … FOR UPDATE 锁住批次行，两人同时收口在锁上串行；</li>
 *   <li>client_token 唯一：断网重试 / 重复扫码第二次撞键，service 幂等返回首次产出；</li>
 *   <li>live_slab_id 唯一：跨批次重复扫码第二次撞键，service 转 409 并回带当前归属。</li>
 * </ul>
 */
@Repository
public class SawBatchRepository {

    @PersistenceContext
    private EntityManager em;

    // ---------------- 批次 ----------------

    public Optional<SawBatch> findById(Long id) {
        return Optional.ofNullable(em.find(SawBatch.class, id));
    }

    /**
     * 收口专用：行级锁加载批次。必须在事务内调用，两个并发收口事务在此串行，
     * 第二个拿到锁后读到已是 CLOSED，按冲突处理，不会产生第二个收口结果。
     */
    public SawBatch lockById(Long id) {
        return em.createQuery("SELECT b FROM SawBatch b WHERE b.id = :id", SawBatch.class)
                .setParameter("id", id)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst().orElse(null);
    }

    public void save(SawBatch batch) {
        if (batch.getId() == null) {
            em.persist(batch);
        } else {
            em.merge(batch);
        }
    }

    public List<SawBatch> listByOrder(Long orderId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawBatch> cq = cb.createQuery(SawBatch.class);
        Root<SawBatch> root = cq.from(SawBatch.class);
        cq.select(root).where(cb.equal(root.get("orderId"), orderId))
                .orderBy(cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    public List<SawBatch> search(SawBatchQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawBatch> cq = cb.createQuery(SawBatch.class);
        Root<SawBatch> root = cq.from(SawBatch.class);
        cq.select(root);
        cq.where(predicates(cb, root, query));
        cq.orderBy(orderBy(cb, root, query));
        TypedQuery<SawBatch> typed = em.createQuery(cq);
        typed.setFirstResult(query.offset());
        typed.setMaxResults(query.limit());
        return typed.getResultList();
    }

    public long count(SawBatchQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<SawBatch> root = cq.from(SawBatch.class);
        cq.select(cb.count(root)).where(predicates(cb, root, query));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 工单全部【已收口】批次固化的合格成品合计——工单完成数的唯一推进口径。 */
    public int sumClosedFinishedByOrder(Long orderId) {
        Long total = em.createQuery(
                        "SELECT COALESCE(SUM(b.closedFinished), 0) FROM SawBatch b "
                                + "WHERE b.orderId = :oid AND b.status = 'CLOSED'", Long.class)
                .setParameter("oid", orderId)
                .getSingleResult();
        return total == null ? 0 : total.intValue();
    }

    public boolean existsByOrder(Long orderId) {
        Long total = em.createQuery("SELECT COUNT(b.id) FROM SawBatch b WHERE b.orderId = :oid", Long.class)
                .setParameter("oid", orderId)
                .getSingleResult();
        return total != null && total > 0;
    }

    /** 全量批次（谱系视图组装用，工厂场景批次量可控）。 */
    public List<SawBatch> listAll() {
        return em.createQuery("SELECT b FROM SawBatch b ORDER BY b.id ASC", SawBatch.class)
                .getResultList();
    }

    public boolean existsByOrderAndStatus(Long orderId, String status) {
        Long total = em.createQuery(
                        "SELECT COUNT(b.id) FROM SawBatch b WHERE b.orderId = :oid AND b.status = :st", Long.class)
                .setParameter("oid", orderId).setParameter("st", status)
                .getSingleResult();
        return total != null && total > 0;
    }

    // ---------------- 冻结排板 ----------------

    public void savePlan(SawPlan plan) {
        em.persist(plan);
    }

    public List<SawPlan> listPlans(Long batchId) {
        return em.createQuery("SELECT p FROM SawPlan p WHERE p.batchId = :bid ORDER BY p.seqNo ASC", SawPlan.class)
                .setParameter("bid", batchId)
                .getResultList();
    }

    // ---------------- 逐片产出 ----------------

    public void saveOutput(SawOutput output) {
        if (output.getId() == null) {
            em.persist(output);
        } else {
            em.merge(output);
        }
    }

    public Optional<SawOutput> findOutput(Long outputId) {
        return Optional.ofNullable(em.find(SawOutput.class, outputId));
    }

    public SawOutput findOutputByToken(String clientToken) {
        return em.createQuery("SELECT o FROM SawOutput o WHERE o.clientToken = :t", SawOutput.class)
                .setParameter("t", clientToken)
                .getResultList().stream().findFirst().orElse(null);
    }

    /** 当前有效（未作废）产出按大板 id 查归属——跨批次占用时给后到方回带先到记录。 */
    public SawOutput findLiveOutputBySlab(Long slabId) {
        return em.createQuery(
                        "SELECT o FROM SawOutput o WHERE o.slabId = :sid AND o.status = 'LIVE'", SawOutput.class)
                .setParameter("sid", slabId)
                .getResultList().stream().findFirst().orElse(null);
    }

    public List<SawOutput> listOutputs(Long batchId) {
        return em.createQuery(
                        "SELECT o FROM SawOutput o WHERE o.batchId = :bid ORDER BY o.id ASC", SawOutput.class)
                .setParameter("bid", batchId)
                .getResultList();
    }

    /** 某产出被多少【其它批次】作为返切来源引用（被引用即禁止作废，谱系链不可断）。 */
    public long countReferencesToOutput(Long outputId) {
        Long total = em.createQuery(
                        "SELECT COUNT(o.id) FROM SawOutput o WHERE o.sourceOutputId = :oid", Long.class)
                .setParameter("oid", outputId)
                .getSingleResult();
        return total == null ? 0L : total;
    }

    // ---------------- 差异待核 ----------------

    public void saveDiff(SawDiff diff) {
        if (diff.getId() == null) {
            em.persist(diff);
        } else {
            em.merge(diff);
        }
    }

    public Optional<SawDiff> findDiff(Long diffId) {
        return Optional.ofNullable(em.find(SawDiff.class, diffId));
    }

    public List<SawDiff> listDiffs(Long batchId) {
        return em.createQuery(
                        "SELECT d FROM SawDiff d WHERE d.batchId = :bid ORDER BY d.createdAt ASC, d.id ASC", SawDiff.class)
                .setParameter("bid", batchId)
                .getResultList();
    }

    public List<SawDiff> listOpenDiffs(Long batchId) {
        return em.createQuery("SELECT d FROM SawDiff d WHERE d.batchId = :bid AND d.status = 'OPEN'", SawDiff.class)
                .setParameter("bid", batchId)
                .getResultList();
    }

    /** 该批次对某类型差异是否已有【已核清】结论（用于重算不复活：负责人确认过的差异不再自动挂回）。 */
    public boolean existsResolvedDiff(Long batchId, String diffType) {
        Long total = em.createQuery(
                        "SELECT COUNT(d.id) FROM SawDiff d WHERE d.batchId = :bid AND d.status = 'RESOLVED' "
                                + "AND d.diffType = :dt", Long.class)
                .setParameter("bid", batchId).setParameter("dt", diffType)
                .getSingleResult();
        return total != null && total > 0;
    }

    // ---------------- 更正审计 / 生命周期留痕 ----------------

    public void saveChange(SawChange change) {
        em.persist(change);
    }

    public List<SawChange> listChanges(Long batchId) {
        return em.createQuery(
                        "SELECT c FROM SawChange c WHERE c.batchId = :bid ORDER BY c.createdAt ASC, c.id ASC",
                        SawChange.class)
                .setParameter("bid", batchId)
                .getResultList();
    }

    /** 大板历次更正（可能跨批次）：按涉及产出收集，供大板详情反查。 */
    public List<SawChange> listChangesByOutputs(List<Long> outputIds) {
        if (outputIds == null || outputIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("SELECT c FROM SawChange c WHERE c.outputId IN :ids ORDER BY c.createdAt ASC, c.id ASC",
                        SawChange.class)
                .setParameter("ids", outputIds)
                .getResultList();
    }

    public List<SawOutput> listOutputsBySlab(Long slabId) {
        return em.createQuery(
                        "SELECT o FROM SawOutput o WHERE o.slabId = :sid ORDER BY o.createdAt ASC, o.id ASC",
                        SawOutput.class)
                .setParameter("sid", slabId)
                .getResultList();
    }

    public void saveEvent(SawEvent event) {
        em.persist(event);
    }

    public List<SawEvent> listEvents(Long batchId) {
        return em.createQuery(
                        "SELECT e FROM SawEvent e WHERE e.batchId = :bid ORDER BY e.createdAt ASC, e.id ASC",
                        SawEvent.class)
                .setParameter("bid", batchId)
                .getResultList();
    }

    public void flush() {
        em.flush();
    }

    // ---------------- Criteria 拼条件 ----------------

    private Predicate[] predicates(CriteriaBuilder cb, Root<SawBatch> root, SawBatchQuery query) {
        List<Predicate> list = new ArrayList<>();
        if (query.getOrderId() != null) {
            list.add(cb.equal(root.get("orderId"), query.getOrderId()));
        }
        if (query.getBlockId() != null) {
            list.add(cb.equal(root.get("blockId"), query.getBlockId()));
        }
        if (has(query.getStatus())) {
            list.add(cb.equal(root.get("status"), query.getStatus()));
        }
        if (has(query.getTeam())) {
            list.add(cb.equal(root.get("team"), query.getTeam()));
        }
        if (has(query.getKeyword())) {
            list.add(cb.or(cb.like(root.get("batchNo"), like(query.getKeyword())),
                    cb.like(root.get("snapOrderNo"), like(query.getKeyword())),
                    cb.like(root.get("snapBlockCode"), like(query.getKeyword()))));
        }
        return list.toArray(new Predicate[0]);
    }

    private List<Order> orderBy(CriteriaBuilder cb, Root<SawBatch> root, SawBatchQuery query) {
        Path<?> path = "code".equals(query.getOrderBy()) ? root.get("batchNo") : root.get("createdAt");
        Order order = query.isDesc() ? cb.desc(path) : cb.asc(path);
        return List.of(order, cb.desc(root.get("id")));
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String like(String value) {
        return "%" + value.trim() + "%";
    }
}
