package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentRecordService;
import com.xinhongru.cms.content.record.SinglePageFieldValues;
import com.xinhongru.cms.uipackage.UiPackageInspectionService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.*;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.service.oneshot.OneShotService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 已确认普通文本计划的私有草稿；不设置公开模板、发布状态或前台入口。 */
@Service
public class TextBindingDraftService {
    public record Result(
            String templatePath,
            boolean initialized,
            Long recordId,
            List<TemplateFieldPlanService.Binding> bound,
            List<TextBindingCompiler.Pending> pending,
            List<String> warnings,
            Map<String, EditorContentPreparation.Prepared> preparedContents,
            String compiledHtml,
            Map<String, String> mediaMappings,
            List<TextBindingCompiler.DomChange> domChanges) {
        public Result(
                String templatePath,
                boolean initialized,
                Long recordId,
                List<TemplateFieldPlanService.Binding> bound,
                List<TextBindingCompiler.Pending> pending,
                List<String> warnings,
                Map<String, EditorContentPreparation.Prepared> preparedContents,
                String compiledHtml,
                Map<String, String> mediaMappings) {
            this(
                    templatePath,
                    initialized,
                    recordId,
                    bound,
                    pending,
                    warnings,
                    preparedContents,
                    compiledHtml,
                    mediaMappings,
                    List.of());
        }
    }

    public record Draft(Result binding, UiPackageInspectionService.Preview preview) {}

    public record Stored(
            String planId,
            String html,
            String phase,
            String message,
            Long recordId,
            boolean existingDetail,
            String attemptId) {
        public Stored(String planId, String html, String phase, String message, Long recordId) {
            this(planId, html, phase, message, recordId, false, null);
        }
    }

    private record Input(
            TemplateFieldPlanService.Plan plan,
            OneshotEntity menu,
            TextBindingCompiler.Compilation compilation,
            List<String> sourceWarnings,
            Map<String, EditorContentPreparation.Prepared> preparedContents,
            Map<String, String> preparedMedia,
            List<TemplateFieldPlanService.Binding> valueBindings,
            TextBindingCompiler.Compilation listCompilation,
            String sourceHtml,
            String listSourceHtml) {}

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.content.record.ContentExtensionResources mediaResources;

    @org.springframework.beans.factory.annotation.Autowired
    private SiteModelPlanService siteModelPlans;

    private final TemplateFieldPlanService plans;
    private final OneShotMapper menus;
    private final OneShotService writer;
    private final ContentRecordService records;
    private final ContentMapper articles;
    private final SinglePageFieldValues values;
    private final UiPackageStorageService packages;
    private final Path privateRoot;
    private final TemplatePreviewService renderer;
    private final UiPackageInspectionService isolation;
    private final tools.jackson.databind.ObjectMapper json =
            new tools.jackson.databind.ObjectMapper();
    private final TextBindingCompiler compiler = new TextBindingCompiler();

    public TextBindingDraftService(
            TemplateFieldPlanService plans,
            OneShotMapper menus,
            OneShotService writer,
            ContentRecordService records,
            ContentMapper articles,
            SinglePageFieldValues values,
            UiPackageStorageService packages,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String privateRoot,
            TemplatePreviewService renderer,
            UiPackageInspectionService isolation) {
        this.plans = plans;
        this.menus = menus;
        this.writer = writer;
        this.records = records;
        this.articles = articles;
        this.values = values;
        this.packages = packages;
        this.privateRoot = Path.of(privateRoot).toAbsolutePath().normalize();
        this.renderer = renderer;
        this.isolation = isolation;
    }

    @Transactional
    public TemplateFieldPlanService.Plan previewSharedDetailTemplate(Long site, String code, String version,
            TemplateFieldPlanService.SharedDetailTemplateRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("共享详情模板计划须通过实际CMS事务入口");
        return plans.previewSharedDetailTemplate(site, code, version, request, plan -> {
            var receipt = read(plan);
            if (receipt == null || !"INITIALIZED".equals(receipt.phase())
                    || !plan.id().equals(receipt.planId()) || receipt.recordId() == null)
                return false;
            var record = articles.selectActiveArticleInSiteMenu(site, Math.toIntExact(plan.menuId()),
                    Math.toIntExact(receipt.recordId()));
            return record != null && Objects.equals(record.getSiteProjectId(), site)
                    && Objects.equals(record.getClassid(), Math.toIntExact(plan.menuId()))
                    && Objects.equals(record.getId().longValue(), receipt.recordId());
        });
    }

    /** 公开应用只检查既有初始化凭据；不绕过私有草稿inspect的前台/发布保护。 */
    public Long requireInitializedRecord(
            TemplateFieldPlanService.Plan plan, TextBindingCompiler.Compilation expected) {
        return requireInitializedRecord(plan, expected, plan);
    }

    Long requireInitializedRecord(
            TemplateFieldPlanService.Plan plan,
            TextBindingCompiler.Compilation expected,
            TemplateFieldPlanService.Plan receiptPlan) {
        var state = read(receiptPlan);
        if (!sameReceiptSource(plan, receiptPlan)
                || state == null
                || !"INITIALIZED".equals(state.phase())
                || !Objects.equals(state.planId(), receiptPlan.id())
                || !Objects.equals(state.html(), expected.html()))
            throw new IllegalArgumentException("原初始化回执与当前页面绑定不一致，请复核来源和字段映射");
        if (plan.pageType() == 1 && (state.recordId() == null || state.recordId() <= 0))
            throw new IllegalArgumentException("原初始化记录身份无效");
        return state.recordId();
    }

    static boolean sameReceiptSource(
            TemplateFieldPlanService.Plan current, TemplateFieldPlanService.Plan original) {
        return Objects.equals(current.siteId(), original.siteId())
                && Objects.equals(current.code(), original.code())
                && Objects.equals(current.packageVersion(), original.packageVersion())
                && Objects.equals(current.menuId(), original.menuId())
                && current.pageType() == original.pageType()
                && Objects.equals(current.sourcePath(), original.sourcePath())
                && Objects.equals(current.digest(), original.digest())
                && Objects.equals(
                        Optional.ofNullable(current.origins()).orElse(List.of()).stream()
                                .map(o -> List.of(o.role(), o.sourcePath(), o.digest())).toList(),
                        Optional.ofNullable(original.origins()).orElse(List.of()).stream()
                                .map(o -> List.of(o.role(), o.sourcePath(), o.digest())).toList());
    }

    @Transactional
    public Result inspect(Long siteId, String code, String version, String planId) {
        var input = current(siteId, code, version, planId);
        var state = read(input.plan());
        checkState(state, input);
        boolean initialized = state != null && "INITIALIZED".equals(state.phase());
        if (initialized && input.plan().pageType() == 1) detail(input, state.recordId());
        return result(input, initialized, state == null ? null : state.recordId());
    }

    @Transactional
    public Result initialize(
            Long siteId,
            String code,
            String version,
            String planId,
            boolean confirmed,
            Long operatorId,
            HttpServletRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("初始化必须通过具有同步上下文的CMS实际事务入口");
        if (!confirmed) throw new IllegalArgumentException("请明确确认使用原文初始化空字段；已有内容不会覆盖");
        if (operatorId == null || operatorId <= 0) throw new IllegalArgumentException("登录用户无效");
        var input = current(siteId, code, version, planId);
        if (input.plan().sharedDetailSeedPlanId() != null)
            throw new IllegalArgumentException("共享详情模板请关联已有草稿并保留其内容");
        plans.assertCurrentPairingReview(input.plan());
        var state = read(input.plan());
        checkState(state, input);
        if (state != null && Set.of("PREPARED", "FAILED").contains(state.phase()))
            throw new IllegalArgumentException("已有未完成应用状态，请先检查持久状态并明确确认恢复；不能重复初始化");
        if (input.plan().pageType() == 1) return initializeDetail(input, state, operatorId);
        if (input.compilation().bound().isEmpty())
            throw new IllegalArgumentException("没有可安全绑定的普通文本，来源结构仍待人工处理");
        if (state != null && "INITIALIZED".equals(state.phase())) return result(input, true);
        assertSeedTarget(input.plan());
        Map<String, Object> initial = initialValues(input);
        if (initial.isEmpty()) {
            render(input, input.menu());
            persist(input, new Stored(
                    planId, input.compilation().html(), "INITIALIZED", "已生成引用栏目名称的私有草稿", null));
            return result(input, true);
        }
        boolean empty = true;
        for (var entry : initial.entrySet()) {
            String existing = value(input.menu(), entry.getKey());
            empty &= existing == null || existing.isBlank();
        }
        if (!empty) throw new IllegalArgumentException("目标字段已有内容，初始化拒绝覆盖；请人工处理冲突");
        var update = new OneshotEntity().setId(input.menu().getId());
        update.setExtensionFields(input.menu().getExtensionFields());
        values.apply(
                update,
                input.menu(),
                ContentModelCodec.read(input.menu().getMenuField()),
                initial,
                siteId);
        var merged = new OneshotEntity();
        BeanUtils.copyProperties(input.menu(), merged);
        values.apply(merged, ContentModelCodec.read(input.menu().getMenuField()), initial, siteId);
        render(input, merged); // 渲染/资源隔离失败发生在模板和业务值写入之前。
        persist(
                input,
                new Stored(
                        planId, input.compilation().html(), "PREPARED", "已准备私有草稿，等待原生内容保存", null));
        try {
            var saved = writer.update(update, request);
            if (saved == null || !Boolean.TRUE.equals(saved.getSuccess()))
                throw new IllegalStateException("CMS原生单页保存未成功");
            completeAfterCommit(input, null);
        } catch (RuntimeException error) {
            persist(
                    input,
                    new Stored(
                            planId, input.compilation().html(), "FAILED", "原生内容保存失败，未完成初始化", null));
            throw new IllegalStateException("初始化保存失败；请检查原值与私有草稿状态，不要盲目覆盖重试", error);
        }
        return result(input, true);
    }

    @Transactional
    public Draft preview(Long siteId, String code, String version, String planId) {
        var input = current(siteId, code, version, planId);
        var state = read(input.plan());
        checkState(state, input);
        if (state == null || !"INITIALIZED".equals(state.phase()))
            throw new IllegalArgumentException("私有草稿尚未完成初始化，不能预览为成功");
        return new Draft(
                result(input, true, state.recordId()),
                input.plan().pageType() == 0
                        ? render(input, input.menu())
                        : renderDetail(input, detail(input, state.recordId())));
    }

    public record ExistingDetailChange(
            String field,
            String name,
            String currentPreview,
            String initialPreview,
            String action) {}

    public record ExistingDetailPreview(
            Long recordId,
            String title,
            String previewDigest,
            List<ExistingDetailChange> changes,
            boolean applicable,
            boolean initialized,
            boolean preserveCurrent) {
        public ExistingDetailPreview(
                Long recordId,
                String title,
                String previewDigest,
                List<ExistingDetailChange> changes,
                boolean applicable,
                boolean initialized) {
            this(recordId, title, previewDigest, changes, applicable, initialized, false);
        }
    }

    private record ExistingDetailSnapshot(
            ExistingDetailPreview preview,
            Map<String, Object> updates,
            Map<String, Object> initial,
            Stored state) {}

    @Transactional
    public ExistingDetailPreview previewExistingDetail(
            Long siteId, String code, String version, String planId, Long recordId) {
        return previewExistingDetail(siteId, code, version, planId, recordId, false);
    }

    @Transactional
    public ExistingDetailPreview previewExistingDetail(
            Long siteId,
            String code,
            String version,
            String planId,
            Long recordId,
            boolean preserveCurrent) {
        var input = current(siteId, code, version, planId);
        return existingDetailSnapshot(input, recordId, preserveCurrent).preview();
    }

    @Transactional
    public Result initializeExistingDetail(
            Long siteId,
            String code,
            String version,
            String planId,
            Long recordId,
            String previewDigest,
            boolean confirmed,
            Long operatorId) {
        return initializeExistingDetail(
                siteId,
                code,
                version,
                planId,
                recordId,
                previewDigest,
                confirmed,
                operatorId,
                false);
    }

    @Transactional
    public Result initializeExistingDetail(
            Long siteId,
            String code,
            String version,
            String planId,
            Long recordId,
            String previewDigest,
            boolean confirmed,
            Long operatorId,
            boolean preserveCurrent) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("补齐详情必须使用实际CMS事务与同步上下文");
        if (!confirmed
                || operatorId == null
                || operatorId <= 0
                || previewDigest == null
                || !previewDigest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("请先预览并明确确认所选草稿及字段变化");
        var input = current(siteId, code, version, planId);
        plans.assertCurrentPairingReview(input.plan());
        var snapshot = existingDetailSnapshot(input, recordId, preserveCurrent);
        if (!snapshot.preview().applicable())
            throw new IllegalArgumentException("目标详情已初始化后发生变更，请核对原值");
        if (snapshot.preview().initialized()) return result(input, true, recordId);
        if (!Objects.equals(previewDigest, snapshot.preview().previewDigest()))
            throw new IllegalArgumentException("来源、绑定状态或当前字段值已变化，请重新预览并确认");

        var prepared =
                new Stored(
                        planId,
                        input.compilation().html(),
                        "PREPARED",
                        preserveCurrent ? "已选定详情草稿，等待确认当前值" : "已选定现有详情草稿，等待校验及保存",
                        recordId,
                        true,
                        UUID.randomUUID().toString());
        if (!compareAndPersistExisting(input, snapshot.state(), prepared))
            throw new IllegalArgumentException("草稿应用状态已变化，请重新预览并确认");
        completeAfterCommit(
                input,
                recordId,
                preserveCurrent ? "已确认所选草稿详情并保留当前值" : "已确认所选草稿详情，私有草稿可授权预览",
                prepared);
        if (!snapshot.updates().isEmpty()) {
            var saved =
                    records.saveDraft(
                            new ContentRecordService.SaveDraftCommand(
                                    siteId,
                                    input.plan().menuId(),
                                    recordId,
                                    null,
                                    snapshot.updates(),
                                    operatorId));
            if (saved == null
                    || !Objects.equals(saved.recordId(), recordId)
                    || !ContentStatus.DRAFT.name().equals(saved.workflowStatus()))
                throw new IllegalStateException("CMS原生详情草稿保存未成功");
        }
        var savedDetail = lockedDetail(input, recordId);
        if (!snapshot.initial().entrySet().stream()
                .allMatch(
                        entry ->
                                Objects.equals(
                                        value(savedDetail, entry.getKey()), entry.getValue())))
            throw new IllegalStateException("详情草稿值与已确认预览不一致，事务拒绝提交");
        validateDetailPages(input, savedDetail);
        return result(input, true, recordId);
    }

    private ExistingDetailSnapshot existingDetailSnapshot(
            Input input, Long recordId, boolean preserveCurrent) {
        if (input.plan().sharedDetailSeedPlanId() != null && !preserveCurrent)
            throw new IllegalArgumentException("共享详情模板只能关联已有草稿并保留其内容");
        if (input.plan().pageType() != 1 || input.compilation().bound().isEmpty())
            throw new IllegalArgumentException("当前计划没有可补齐的详情字段");
        var state = read(input.plan());
        checkState(state, input);
        boolean initialized = state != null && "INITIALIZED".equals(state.phase());
        if (state != null
                && (!Objects.equals(state.recordId(), recordId)
                        || (!initialized && !state.existingDetail())))
            throw new IllegalArgumentException("已有绑定或恢复记录，请核对原记录，不能更换详情草稿");
        if (preserveCurrent) {
            var plan = input.plan();
            if (siteModelPlans != null)
                siteModelPlans.assertDerivedCurrent(
                        plan.siteId(), plan.code(), plan.packageVersion(), plan.id());
        } else {
            assertSeedTarget(input.plan());
        }
        if (state == null) assertNewDetailIdentity(input);
        var record = lockedDetail(input, recordId);
        Map<String, Object> initial =
                preserveCurrent ? new LinkedHashMap<>() : initialValues(input);
        var currentValues = new LinkedHashMap<String, String>();
        var updates = new LinkedHashMap<String, Object>();
        var changes = new ArrayList<ExistingDetailChange>();
        for (var binding : input.valueBindings()) {
            String field = binding.fieldName();
            String actual = value(record, field);
            if (preserveCurrent) initial.put(field, actual);
            String original = (String) initial.get(field);
            currentValues.put(field, actual);
            String action = "KEEP";
            if (!Objects.equals(actual, original)) {
                action = actual == null || actual.isBlank() ? "FILL" : "REPLACE";
                updates.put(field, original);
            }
            changes.add(
                    new ExistingDetailChange(
                            field,
                            binding.name(),
                            shortValue(actual),
                            shortValue(original),
                            action));
        }
        var digestValues =
                Arrays.asList(
                        input.plan().id(),
                        input.plan().afterFieldConfigDigest(),
                        input.plan().digest(),
                        input.plan().origins(),
                        input.compilation().html(),
                        input.listCompilation() == null ? null : input.listCompilation().html(),
                        state,
                        recordId,
                        record.getTitle(),
                        record.getSort(),
                        currentValues,
                        initial);
        String digest =
                recoveryDigest(preserveCurrent ? List.of(true, digestValues) : digestValues);
        return new ExistingDetailSnapshot(
                new ExistingDetailPreview(
                        recordId,
                        record.getTitle(),
                        digest,
                        List.copyOf(changes),
                        !initialized || updates.isEmpty(),
                        initialized,
                        preserveCurrent),
                updates,
                initial,
                state);
    }

    /** 双来源的列表私有预览；仅当前卡片使用同一草稿记录，其余静态区域保持原样。 */
    @Transactional
    public Draft previewList(Long siteId, String code, String version, String planId) {
        var input = current(siteId, code, version, planId);
        if (input.listCompilation() == null) throw new IllegalArgumentException("此计划不含列表来源");
        var state = read(input.plan());
        checkState(state, input);
        if (state == null || !"INITIALIZED".equals(state.phase()))
            throw new IllegalArgumentException("私有草稿尚未完成初始化，不能预览为成功");
        var record = detail(input, state.recordId());
        return new Draft(result(input, true, state.recordId()), renderList(input, record));
    }

    public record RecoverRequest(
            String action, boolean confirmed, String stateDigest, String currentValueDigest) {}

    public record Difference(
            String field,
            String name,
            String initialPreview,
            String currentPreview,
            boolean equal) {}

    public record Recovery(
            String phase,
            Long recordId,
            String message,
            List<String> actions,
            String stateDigest,
            String currentValueDigest,
            List<Difference> differences,
            String activePlanId) {}

    private Recovery publishedRecovery(TemplateFieldPlanService.Plan plan) {
        if (plan.pageType() != 0 && plan.pageType() != 1) return null;
        var menu = menus.selectPublicBindingMenu(plan.siteId(), plan.menuId());
        if (menu == null || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                || !Objects.equals(menu.getPageType(), plan.pageType())
                || !Objects.equals(menu.getSiteProjectId(), plan.siteId())
                || !Objects.equals(menu.getId().longValue(), plan.menuId())
                || !ContentFieldConfigDigest.of(menu).equals(plan.afterFieldConfigDigest()))
            return null;
        var application = PublicBindingApplication.atPath(privateRoot, plan.code(),
                plan.pageType() == 0 ? menu.getListPageTemplate() : menu.getDetailPageTemplate());
        if (application == null || application.queryId() != null || application.routeMenuId() != null
                || application.navigationLanding() != null || application.pageType() != plan.pageType()
                || !Objects.equals(application.menuId(), plan.menuId())
                || !Objects.equals(application.siteId(), plan.siteId())
                || !Objects.equals(application.packageVersion(), plan.packageVersion()))
            return null;
        var initial = application.initial(privateRoot, plan.code());
        var receiptPlan = plans.readForWithdrawal(plan.siteId(), plan.code(), plan.packageVersion(), initial.planId());
        if (!sameReceiptSource(plan, receiptPlan)) return null;
        var source = packages.readStaticPage(plan.code(), plan.packageVersion(), plan.sourcePath());
        if (!Objects.equals(source.digest(), plan.digest())) return null;
        var expected = compiler.compile(source.html(), TextBindingCompiler.publicBindings(plan), plan.sources(),
                plan.pageType(), true, plan.schemaVersion() >= 3);
        var receipt = read(receiptPlan);
        if (receipt == null || !"INITIALIZED".equals(receipt.phase())
                || !Objects.equals(receipt.planId(), receiptPlan.id())
                || !Objects.equals(receipt.html(), expected.html())
                || !Objects.equals(application.initializedRecordId(), receipt.recordId())) return null;
        return new Recovery("INITIALIZED", receipt.recordId(), "可沿用已发布页面的初始化回执，请进入预览与公开核对",
                List.of(), recoveryDigest(receipt), null, List.of(), receiptPlan.id());
    }

    private record RecoverySnapshot(
            Input input, Stored state, ContentEntity detail, Recovery view, boolean safeCurrent) {}

    @Transactional
    public Recovery recovery(Long siteId, String code, String version, String id) {
        return recoverySnapshot(siteId, code, version, id).view();
    }

    @Transactional
    public Result recover(
            Long siteId,
            String code,
            String version,
            String id,
            RecoverRequest request,
            Long operatorId,
            HttpServletRequest httpRequest) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("恢复必须使用实际CMS事务与同步上下文");
        if (request == null
                || !request.confirmed()
                || operatorId == null
                || operatorId <= 0
                || request.action() == null
                || !Set.of("KEEP_SINGLE_CURRENT", "RESUME_EXISTING_DETAIL", "RETRY_MISSING_DETAIL")
                        .contains(request.action()))
            throw new IllegalArgumentException("请明确确认有效的恢复动作及登录用户");
        var snapshot = recoverySnapshot(siteId, code, version, id);
        var input = snapshot.input();
        var state = snapshot.state();
        if (input == null || state == null && !snapshot.view().actions().contains(request.action()))
            throw new IllegalArgumentException("来源/配置已变化或没有失败状态，不能恢复");
        boolean singleAction = request.action().equals("KEEP_SINGLE_CURRENT");
        if (singleAction != (input.plan().pageType() == 0))
            throw new IllegalArgumentException("恢复动作与目标类型不匹配");
        if (state != null && "INITIALIZED".equals(state.phase()) && snapshot.safeCurrent())
            return result(input, true, state.recordId());
        if (!snapshot.view().actions().contains(request.action()))
            throw new IllegalArgumentException("当前状态不允许此恢复：" + snapshot.view().message());
        if (!Objects.equals(request.stateDigest(), snapshot.view().stateDigest())
                || !Objects.equals(
                        request.currentValueDigest(), snapshot.view().currentValueDigest()))
            throw new IllegalArgumentException("恢复状态或当前字段值已变化，请重新检查并确认");
        if (singleAction) {
            persist(
                    input,
                    new Stored(
                            id,
                            input.compilation().html(),
                            "PREPARED",
                            "保留现有字段，等待草稿确认；未写业务字段",
                            null));
            completeAfterCommit(input, null, "已保留现有字段并恢复私有草稿确认，未写业务字段");
            return result(input, true);
        }
        if (request.action().equals("RESUME_EXISTING_DETAIL")) {
            validateDetailPages(input, snapshot.detail());
            persist(
                    input,
                    new Stored(
                            id,
                            input.compilation().html(),
                            "PREPARED",
                            "保留现有详情，等待确认",
                            state.recordId()));
            completeAfterCommit(input, state.recordId(), "已确认现有详情草稿，未覆盖人工值");
            return result(input, true, state.recordId());
        }
        assertSeedTarget(input.plan());
        var reset =
                new Stored(
                        id, input.compilation().html(), "PREPARED", "已明确确认失败详情确实不存在，按原生流程重试", null);
        persist(input, reset);
        return initializeDetail(input, reset, operatorId);
    }

    private RecoverySnapshot recoverySnapshot(Long siteId, String code, String version, String id) {
        var authorizedPlan = plans.readForWithdrawal(siteId, code, version, id);
        if (authorizedPlan == null) throw new IllegalArgumentException("恢复计划不存在或归属无效");
        Stored state;
        Input input;
        try {
            input = current(siteId, code, version, id);
            state = read(input.plan());
            checkState(state, input);
        } catch (IllegalArgumentException | org.springframework.web.server.ResponseStatusException error) {
            var published = publishedRecovery(authorizedPlan);
            if (published != null) return new RecoverySnapshot(null, null, null, published, false);
            state = read(authorizedPlan);
            return new RecoverySnapshot(
                    null,
                    state,
                    null,
                    new Recovery(
                            state == null ? "NONE" : state.phase(),
                            state == null ? null : state.recordId(),
                            error instanceof org.springframework.web.server.ResponseStatusException archived
                                    ? archived.getReason() : error.getMessage(),
                            List.of(),
                            recoveryDigest(state),
                            null,
                            List.of(),
                            state == null ? null : state.planId()),
                    false);
        }
        if (state != null && state.existingDetail()) {
            String message =
                    "INITIALIZED".equals(state.phase()) ? "已完成所选草稿的详情补齐" : "请重新预览并确认同一草稿的详情补齐";
            return new RecoverySnapshot(
                    input,
                    state,
                    null,
                    new Recovery(
                            state.phase(),
                            state.recordId(),
                            message,
                            List.of(),
                            recoveryDigest(state),
                            null,
                            List.of(),
                            state.planId()),
                    false);
        }
        var actions = new ArrayList<String>();
        String message = "尚未初始化，请使用正常初始化入口";
        Object owner = input.plan().pageType() == 0 ? input.menu() : null;
        ContentEntity detail = null;
        boolean missing = state == null || state.recordId() == null;
        if (input.plan().pageType() == 1 && !missing) {
            if (state.recordId() <= 0 || state.recordId() > Integer.MAX_VALUE)
                message = "详情标识无效，禁止重建";
            else {
                var identity = articles.lockTextDraftRecoveryIdentity(state.recordId().intValue());
                missing = identity == null;
                if (!missing) {
                    boolean own =
                            number(identity.get("id")) == state.recordId()
                                    && number(identity.get("site_project_id")) == siteId
                                    && number(identity.get("class_id")) == input.plan().menuId()
                                    && number(identity.get("deleted")) == 0
                                    && number(identity.get("status")) == ContentStatus.DRAFT.code();
                    if (own) {
                        detail =
                                articles.lockActiveArticleInSiteMenu(
                                        siteId,
                                        input.plan().menuId().intValue(),
                                        state.recordId().intValue());
                        if (detail != null
                                && Objects.equals(detail.getSiteProjectId(), siteId)
                                && Objects.equals(
                                        detail.getClassid(), Math.toIntExact(input.plan().menuId()))
                                && Objects.equals(detail.getId(), Math.toIntExact(state.recordId()))
                                && Objects.equals(detail.getStatus(), ContentStatus.DRAFT.code()))
                            owner = detail;
                    }
                    message = owner == null ? "详情仍存在但已删除、错属或非DRAFT，禁止按缺失重建" : "详情存在，恢复只确认当前值";
                }
            }
        }
        var snapshot = new LinkedHashMap<String, String>();
        var differences = new ArrayList<Difference>();
        boolean safe = false;
        var initial = initialValues(input);
        for (var binding : input.valueBindings()) {
            String actual = owner == null ? null : value(owner, binding.fieldName());
            if (owner != null) snapshot.put(binding.fieldName(), actual);
            String original = String.valueOf(initial.get(binding.fieldName()));
            differences.add(
                    new Difference(
                            binding.fieldName(),
                            binding.name(),
                            shortValue(original),
                            shortValue(actual),
                            Objects.equals(original, actual)));
        }
        if (owner != null && !input.valueBindings().isEmpty()) {
            try {
                if (input.plan().pageType() == 0) {
                    var copy = new OneshotEntity();
                    BeanUtils.copyProperties(input.menu(), copy);
                    var currentContent = new ContentEntity();
                    BeanUtils.copyProperties(copy, currentContent);
                    records.validateCurrentValues(
                            currentContent, ContentModelCodec.read(copy.getMenuField()), siteId);
                    values.apply(
                            copy, ContentModelCodec.read(copy.getMenuField()), Map.of(), siteId);
                    for (var field : ContentModelCodec.read(copy.getMenuField()))
                        if (ContentFieldDefinition.enabled(field.getIsOpen())
                                && ContentFieldDefinition.enabled(field.getIsRequired())
                                && snapshot.containsKey(field.getFieldName())
                                && (snapshot.get(field.getFieldName()) == null
                                        || snapshot.get(field.getFieldName()).isBlank()))
                            throw new IllegalArgumentException("当前必填字段为空");
                    render(input, copy);
                } else {
                    records.validateCurrentValues(
                            detail, ContentModelCodec.read(input.menu().getModelField()), siteId);
                    validateDetailPages(input, detail);
                }
                safe = true;
            } catch (IllegalArgumentException error) {
                message = "当前值不能恢复：" + error.getMessage();
            }
        }
        if (state == null
                && input.plan().pageType() == 0
                && safe
                && input.valueBindings().stream()
                        .anyMatch(
                                binding ->
                                        "REUSE".equals(binding.action())
                                                && snapshot.get(binding.fieldName()) != null
                                                && !snapshot.get(binding.fieldName()).isBlank())) {
            actions.add("KEEP_SINGLE_CURRENT");
            message = "确认保留当前字段值并生成私有草稿";
        } else if (state != null) {
            if ("INITIALIZED".equals(state.phase()))
                message = safe ? "已完成；重复确认不新增、不覆盖当前值" : message;
            else if (input.plan().pageType() == 0
                    && safe
                    && Set.of("PREPARED", "FAILED").contains(state.phase())) {
                actions.add("KEEP_SINGLE_CURRENT");
                message = "确认保留当前字段值，仅修复私有草稿确认；不会填充或覆盖字段";
            } else if (input.plan().pageType() == 1
                    && safe
                    && Set.of("PREPARED", "FAILED").contains(state.phase()))
                actions.add("RESUME_EXISTING_DETAIL");
            else if (input.plan().pageType() == 1
                    && missing
                    && !input.valueBindings().isEmpty()
                    && "FAILED".equals(state.phase())) {
                actions.add("RETRY_MISSING_DETAIL");
                message = "失败尝试的记录确实不存在；明确确认后才按原生流程创建新草稿";
            }
        }
        var view =
                new Recovery(
                        state == null ? "NONE" : state.phase(),
                        state == null ? null : state.recordId(),
                        message,
                        List.copyOf(actions),
                        recoveryDigest(state),
                        recoveryDigest(snapshot),
                        List.copyOf(differences),
                        state == null ? null : state.planId());
        return new RecoverySnapshot(input, state, detail, view, safe);
    }

    private static long number(Object value) {
        return value instanceof Number number
                ? number.longValue()
                : Boolean.FALSE.equals(value) ? 0 : Boolean.TRUE.equals(value) ? 1 : Long.MIN_VALUE;
    }

    private static String shortValue(String value) {
        return value == null
                ? "（空）"
                : value.length() > 1000 ? value.substring(0, 1000) + "（展示截断）" : value;
    }

    private String recoveryDigest(Object value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            new tools.jackson.databind.ObjectMapper()
                                                    .writeValueAsBytes(value)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Input current(Long siteId, String code, String version, String id) {
        var flight = plans.preflight(siteId, code, version, id);
        var plan = flight.plan();
        if (flight.requiresFieldUpdate()
                || !Set.of("CONFIRMED", "CONFIRMED_WITH_PENDING").contains(plan.status()))
            throw new IllegalArgumentException("请先确认当前字段计划");
        if (plan.pageType() != 0 && plan.pageType() != 1)
            throw new IllegalArgumentException("仅支持单页或列表详情");
        var menu = menus.lockFieldConfiguration(siteId, plan.menuId());
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), siteId)
                || !Objects.equals(menu.getId().longValue(), plan.menuId())
                || !Objects.equals(menu.getPageType(), plan.pageType())
                || !Boolean.TRUE.equals(menu.getHasManageChannel()))
            throw new IllegalArgumentException("目标栏目不存在、已删除或不属于当前站点");
        if (!ContentFieldConfigDigest.of(menu).equals(plan.afterFieldConfigDigest()))
            throw new IllegalArgumentException("字段配置已变化，请重新确认计划");
        // Oneshot栏目发布状态为3；内容记录另按ContentStatus判断，二者不能混用。
        if (!Boolean.FALSE.equals(menu.getHasFrontChannel()) || Objects.equals(menu.getStatus(), 3))
            throw new IllegalArgumentException("本期只允许未开放前台且未发布的新站栏目草稿");
        var source = packages.readStaticPage(code, version, plan.sourcePath());
        if (!Objects.equals(source.digest(), plan.digest()))
            throw new IllegalArgumentException("页面或包含片段已变化，请重新确认来源与字段计划");
        String html = source.html();
        boolean paired = plan.origins() != null && !plan.origins().isEmpty();
        TemplateFieldPlanService.Origin listOrigin =
                paired
                        ? plan.origins().stream()
                                .filter(origin -> "LIST".equals(origin.role()))
                                .findFirst()
                                .orElseThrow()
                        : null;
        var listSource =
                listOrigin == null
                        ? null
                        : packages.readStaticPage(code, version, listOrigin.sourcePath());
        if (listSource != null && !Objects.equals(listSource.digest(), listOrigin.digest()))
            throw new IllegalArgumentException("列表页面或包含片段已变化，请重新确认双来源计划");

        var enabled = new HashMap<String, ContentFieldDefinition>();
        for (var field :
                ContentModelCodec.read(
                        plan.pageType() == 0 ? menu.getMenuField() : menu.getModelField()))
            enabled.put(field.getFieldName(), field);
        var ordinary = new ArrayList<TemplateFieldPlanService.Binding>();
        var subtypePending = new ArrayList<TextBindingCompiler.Pending>();
        for (var binding : plan.bindings()) {
            var field = enabled.get(binding.fieldName());
            if (isDeferredSubtype(binding, field)) {
                subtypePending.add(
                        new TextBindingCompiler.Pending(
                                binding.unitId(), "选项或关联子类型仍待处理；不能把枚举编码或关联ID直接当作普通文本"));
            } else ordinary.add(binding);
        }
        var detailBindings =
                ordinary.stream().filter(binding -> !isListBinding(plan, binding)).toList();
        var compiled =
                compiler.compile(
                        html,
                        detailBindings,
                        plan.sources(),
                        plan.pageType(),
                        true,
                        plan.schemaVersion() >= 3);
        var listCompiled =
                listSource == null
                        ? null
                        : compiler.compile(
                                listSource.html(),
                                ordinary.stream()
                                        .filter(binding -> isListBinding(plan, binding))
                                        .toList(),
                                plan.sources(),
                                1,
                                true,
                                plan.schemaVersion() >= 3);
        boolean legacyScope = false;
        var stored = read(plan);
        if (stored != null
                && Objects.equals(stored.planId(), plan.id())
                && !Objects.equals(stored.html(), compiled.html())) {
            var legacy =
                    compiler.compile(
                            html,
                            detailBindings,
                            plan.sources(),
                            plan.pageType(),
                            false,
                            plan.schemaVersion() >= 3);
            if (Objects.equals(stored.html(), legacy.html())) {
                compiled = legacy;
                listCompiled =
                        listSource == null
                                ? null
                                : compiler.compile(
                                        listSource.html(),
                                        ordinary.stream()
                                                .filter(binding -> isListBinding(plan, binding))
                                                .toList(),
                                        plan.sources(),
                                        1,
                                        false,
                                        plan.schemaVersion() >= 3);
                legacyScope = true;
            }
        }
        var pending = new ArrayList<>(compiled.pending());
        if (listCompiled != null) pending.addAll(listCompiled.pending());
        pending.addAll(subtypePending);
        if (paired
                && (!pending.isEmpty()
                        || compiled.bound().isEmpty()
                        || listCompiled.bound().isEmpty()))
            throw new IllegalArgumentException("双来源绑定仍有待处理，不能部分初始化此条目");
        var compilation =
                new TextBindingCompiler.Compilation(
                        compiled.html(),
                        compiled.bound(),
                        List.copyOf(pending),
                        compiled.domChanges());
        var valueBindings = new ArrayList<>(compiled.bound());
        if (listCompiled != null) valueBindings.addAll(listCompiled.bound());
        valueBindings.removeIf(MenuNameBinding::applies);
        for (var binding : valueBindings) {
            var field = enabled.get(binding.fieldName());
            if (field == null
                    || !ContentFieldDefinition.enabled(field.getIsOpen())
                    || ContentFieldDefinition.enabled(field.getIsReadOnly())
                    || "content".equals(field.getInputType())
                    || !Set.of("SingleLine", "MultiLine", "Editor", "Image", "Video", "DateTime")
                            .contains(field.getFieldType())
                    || !Objects.equals(field.getFieldType(), binding.fieldType()))
                throw new IllegalArgumentException("绑定字段已停用、只读或类型不支持");
            if ("DateTime".equals(binding.fieldType())) {
                TemplateDateValues.original(binding, plan.sources());
                TemplateDateValues.validateField(binding, field);
            }
        }
        var prepared = new LinkedHashMap<String, EditorContentPreparation.Prepared>();
        for (var binding : valueBindings)
            if ("Editor".equals(binding.fieldType())) {
                var evidence =
                        plan.sources().stream()
                                .filter(
                                        item ->
                                                item.id()
                                                        .equals(
                                                                binding.sources()
                                                                        .getFirst()
                                                                        .sourceId()))
                                .findFirst()
                                .orElseThrow();
                prepared.put(
                        binding.unitId(),
                        new EditorContentPreparation(packages)
                                .prepare(
                                        code,
                                        version,
                                        sourcePathFor(plan, binding),
                                        evidence.rawValue()));
            }
        var media = new LinkedHashMap<String, String>();
        for (var binding : valueBindings)
            if (Set.of("Image", "Video").contains(binding.fieldType())) {
                var evidence =
                        plan.sources().stream()
                                .filter(
                                        item ->
                                                item.id()
                                                        .equals(
                                                                binding.sources()
                                                                        .getFirst()
                                                                        .sourceId()))
                                .findFirst()
                                .orElseThrow();
                String reference =
                        evidence.rawValue().startsWith("assets:/")
                                ? evidence.rawValue()
                                : EditorContentPreparation.packageReference(
                                        sourcePathFor(plan, binding), evidence.rawValue());
                media.put(
                        binding.unitId(),
                        mediaResources.prepareTemplateMedia(
                                enabled.get(binding.fieldName()), reference, siteId));
            }
        var sourceWarnings = new ArrayList<String>(source.warnings());
        if (legacyScope) sourceWarnings.add("沿用此私有草稿原绑定范围；新增背景联动需重新确认字段计划，不会补写或覆盖原内容");
        return new Input(
                plan,
                menu,
                compilation,
                java.util.stream.Stream.concat(
                                sourceWarnings.stream(),
                                listSource == null
                                        ? java.util.stream.Stream.<String>empty()
                                        : listSource.warnings().stream())
                        .distinct()
                        .toList(),
                Map.copyOf(prepared),
                Map.copyOf(media),
                List.copyOf(valueBindings),
                listCompiled,
                html,
                listSource == null ? null : listSource.html());
    }

    static boolean isDeferredSubtype(
            TemplateFieldPlanService.Binding binding, ContentFieldDefinition field) {
        return !"IGNORED".equals(binding.action())
                && field != null
                && "SingleLine".equals(field.getFieldType())
                && field.getInputType() != null
                && !field.getInputType().isBlank()
                && !com.xinhongru.content.model.CounterNumbers.applies(field);
    }

    static boolean isListBinding(
            TemplateFieldPlanService.Plan plan, TemplateFieldPlanService.Binding binding) {
        if (plan.origins() == null || plan.origins().isEmpty()) return false;
        if (binding.sources() == null || binding.sources().isEmpty())
            throw new IllegalArgumentException("双来源绑定缺少来源引用");
        boolean list = binding.sources().getFirst().sourceId().startsWith("LIST:");
        String prefix = list ? "LIST:" : "DETAIL:";
        if (binding.sources().stream().anyMatch(ref -> !ref.sourceId().startsWith(prefix)))
            throw new IllegalArgumentException("单个字段不能混合两个页面的来源区间");
        return list;
    }

    private static String sourcePathFor(
            TemplateFieldPlanService.Plan plan, TemplateFieldPlanService.Binding binding) {
        if (plan.origins() == null || plan.origins().isEmpty()) return plan.sourcePath();
        String role = isListBinding(plan, binding) ? "LIST" : "DETAIL";
        return plan.origins().stream()
                .filter(origin -> role.equals(origin.role()))
                .findFirst()
                .orElseThrow()
                .sourcePath();
    }

    private Map<String, Object> initialValues(Input input) {
        var sources = new HashMap<String, TemplateContentUnitsService.Source>();
        for (var source : input.plan().sources()) sources.put(source.id(), source);
        var detailDocument = org.jsoup.Jsoup.parse(input.compilation().html());
        var listDocument =
                input.listCompilation() == null
                        ? null
                        : org.jsoup.Jsoup.parse(input.listCompilation().html());
        var detailOriginal = org.jsoup.Jsoup.parse(input.sourceHtml());
        var listOriginal =
                input.listSourceHtml() == null
                        ? null
                        : org.jsoup.Jsoup.parse(input.listSourceHtml());
        var result = new LinkedHashMap<String, Object>();
        for (var binding : input.valueBindings()) {
            var ref = binding.sources().getFirst();
            var source = sources.get(ref.sourceId());
            String raw = source.rawValue();
            String original =
                    "Editor".equals(binding.fieldType())
                            ? input.preparedContents().get(binding.unitId()).valueHtml()
                            : raw.substring(ref.start(), ref.end());
            if ("DateTime".equals(binding.fieldType()))
                original =
                        TemplateDateValues.initial(
                                TemplateDateValues.original(binding, input.plan().sources()));
            if ("MultiLine".equals(binding.fieldType())) {
                var document = isListBinding(input.plan(), binding) ? listDocument : detailDocument;
                var selected = document.select(source.selector());
                if (selected.size() != 1) throw new IllegalArgumentException("多行初值来源定位不唯一");
                String owner = input.plan().pageType() == 0 ? "currentMenu" : "content";
                String expression =
                        ContentExtensionFields.isName(binding.fieldName())
                                ? "${" + owner + ".extensionValues['" + binding.fieldName() + "']}"
                                : "${" + owner + "." + binding.fieldName() + "}";
                var slots =
                        selected.first().select("span[data-cms-multiline=true]").stream()
                                .filter(
                                        slot ->
                                                "white-space: pre-wrap".equals(slot.attr("style"))
                                                        && expression.equals(slot.attr("th:text")))
                                .toList();
                if (slots.size() != 1) throw new IllegalArgumentException("多行初值与受控编译slot不一致");
                // 只读本次编译器核验生成且目标字段完全匹配的slot，不改变原始来源或用户后来保存值。
                var originalDocument =
                        isListBinding(input.plan(), binding) ? listOriginal : detailOriginal;
                var originalElements = originalDocument.select(source.selector());
                if (originalElements.size() != 1)
                    throw new IllegalArgumentException("多行首次初值原DOM定位不唯一");
                original =
                        TextBindingCompiler.initialMultilineText(
                                originalElements.first(),
                                source,
                                ref,
                                slots.getFirst().wholeText(),
                                input.plan().schemaVersion() >= 3 && input.plan().pageType() == 1
                                        && !isListBinding(input.plan(), binding));
            }
            if (Set.of("Image", "Video").contains(binding.fieldType()))
                original = input.preparedMedia().get(binding.unitId());
            if (original.length() > binding.capacity()
                    || ("SingleLine".equals(binding.fieldType())
                            && (original.contains("\n") || original.contains("\r"))))
                throw new IllegalArgumentException("原文字数或换行不符合确认字段容量");
            if (result.putIfAbsent(binding.fieldName(), original) != null)
                throw new IllegalArgumentException("多个来源映射同一字段，需先人工合并或修正");
        }
        return result;
    }

    private String value(Object item, String name) {
        boolean single = item instanceof OneshotEntity;
        if (!(single || item instanceof ContentEntity))
            throw new IllegalArgumentException("无效的内容实体");
        if (ContentExtensionFields.isName(name)) {
            Object stored =
                    (single
                                    ? ((OneshotEntity) item).getExtensionValues()
                                    : ((ContentEntity) item).getExtensionValues())
                            .get(name);
            if (stored != null && !(stored instanceof String))
                throw new IllegalArgumentException("当前字段值不是文本: " + name);
            return (String) stored;
        }
        try {
            var field = EntityFields.find(single ? OneshotEntity.class : ContentEntity.class, name);
            field.setAccessible(true);
            Object stored = field.get(item);
            if (stored instanceof java.time.LocalDateTime dateTime)
                return java.time.LocalTime.MIDNIGHT.equals(dateTime.toLocalTime())
                        ? dateTime.toLocalDate().toString()
                        : dateTime.toString();
            return (String) stored;
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("目标文本字段不存在", error);
        }
    }

    private UiPackageInspectionService.Preview render(Input input, OneshotEntity menu) {
        String rendered =
                renderer.renderControlledDraft(input.plan().code(), input.compilation(), menu);
        return isolation.isolateRendered(
                input.plan().code(),
                input.plan().packageVersion(),
                input.plan().sourcePath(),
                rendered);
    }

    private Result result(Input input, boolean initialized) {
        return result(input, initialized, null);
    }

    private Result result(Input input, boolean initialized, Long recordId) {
        var warnings = new ArrayList<>(input.sourceWarnings());
        warnings.addAll(
                List.of(
                        "私有草稿位于公开模板根与UI包版本之外；只经授权工作台读取，不发布、不设置公开模板路径。",
                        "支持普通单行及完整多行容器；多行格式换行可能可视，请核对预览。混合排版、图片、多来源及其他类型保留待处理。",
                        input.plan().pageType() == 1
                                ? "详情仅允许DRAFT；待审、发布、驳回、归档及未知状态不进入本次私有预览。"
                                : "单页保留原工作流状态，不设置公开模板或开放前台。"));
        var domChanges = new ArrayList<>(input.compilation().domChanges());
        if (input.listCompilation() != null)
            domChanges.addAll(input.listCompilation().domChanges());
        return new Result(
                "private:text-drafts/" + input.plan().id(),
                initialized,
                recordId,
                input.compilation().bound(),
                input.compilation().pending(),
                List.copyOf(warnings),
                input.preparedContents(),
                input.compilation().html(),
                input.preparedMedia(),
                List.copyOf(domChanges));
    }

    private Result initializeDetail(Input input, Stored state, Long operatorId) {
        if (input.compilation().bound().isEmpty())
            throw new IllegalArgumentException("没有可安全绑定的普通文本，来源结构仍待人工处理");
        var plan = input.plan();
        var initial = initialValues(input);
        if (state != null && state.recordId() != null) {
            var existing = detail(input, state.recordId());
            if ("INITIALIZED".equals(state.phase())) return result(input, true, state.recordId());
            // PREPARED中的ID已在同一数据库事务提交前持久化；重试只确认其现值，不覆盖提交后的人工编辑。
            validateDetailPages(input, existing);
            persist(
                    input,
                    new Stored(
                            plan.id(),
                            input.compilation().html(),
                            "INITIALIZED",
                            "恢复已有详情草稿确认记录",
                            state.recordId()));
            return result(input, true, state.recordId());
        }
        if (initial.isEmpty())
            throw new IllegalArgumentException("栏目名称引用无需初始化字段，请选择现有详情草稿生成预览");
        if (TemplateFieldPlanService.usesSharedDetail(plan)) {
            if (!(initial.get("title") instanceof String title) || title.isBlank())
                throw new IllegalArgumentException("共享详情草稿须使用当前列表卡片标题");
            if (articles.selectPairingTitleCandidates(plan.siteId(), Math.toIntExact(plan.menuId()), title)
                    .stream().anyMatch(row -> Objects.equals(row.getClassid(), Math.toIntExact(plan.menuId()))))
                throw new IllegalArgumentException("当前栏目已有同标题记录，请先审核已有值及记录归属");
        }
        assertSeedTarget(plan);
        assertNewDetailIdentity(input);
        persist(
                input,
                new Stored(
                        plan.id(), input.compilation().html(), "PREPARED", "等待CMS原生详情草稿保存", null));
        Long recordId = null;
        try {
            var saved =
                    records.saveDraft(
                            new ContentRecordService.SaveDraftCommand(
                                    plan.siteId(), plan.menuId(), null, null, initial, operatorId));
            if (saved == null
                    || saved.recordId() == null
                    || saved.recordId() <= 0
                    || !ContentStatus.DRAFT.name().equals(saved.workflowStatus()))
                throw new IllegalStateException("CMS原生详情草稿保存未成功");
            recordId = saved.recordId();
            var created = detail(input, recordId);
            if (!Objects.equals(created.getStatus(), ContentStatus.DRAFT.code())
                    || !initial.entrySet().stream()
                            .allMatch(
                                    entry ->
                                            Objects.equals(
                                                    value(created, entry.getKey()),
                                                    entry.getValue())))
                throw new IllegalStateException("原生详情草稿状态或原文值不一致，事务拒绝提交");
            validateDetailPages(input, created);
            // 提交前持久化已分配ID；即使确认文件失败，重试也不再插入第二条内容。
            persist(
                    input,
                    new Stored(
                            plan.id(),
                            input.compilation().html(),
                            "PREPARED",
                            "已分配详情草稿ID，等待事务提交",
                            recordId));
            completeAfterCommit(input, recordId);
            return result(input, true, recordId);
        } catch (RuntimeException error) {
            persist(
                    input,
                    new Stored(
                            plan.id(),
                            input.compilation().html(),
                            "FAILED",
                            "详情保存或渲染失败，未完成初始化；请核对原记录状态",
                            recordId));
            throw new IllegalStateException("详情初始化保存或渲染失败；未发布，请核对草稿状态", error);
        }
    }

    private void assertSeedTarget(TemplateFieldPlanService.Plan plan) {
        if (siteModelPlans != null)
            siteModelPlans.assertDerivedSeedTarget(
                    plan.siteId(),
                    plan.code(),
                    plan.packageVersion(),
                    plan.id(),
                    plan.sourcePath(),
                    plan.menuId());
    }

    private void assertNewDetailIdentity(Input input) {
        var plan = input.plan();
        var stable = read(plan.code(), TemplateFieldPlanService.detailApplicationId(plan));
        if (stable != null && !Objects.equals(stable.planId(), plan.id()))
            throw new IllegalArgumentException(
                    "同一详情已有私有草稿应用，请读取原计划 " + stable.planId() + " 恢复；不能新增替代记录");
        if (plan.origins() != null && !plan.origins().isEmpty() && !TemplateFieldPlanService.usesSharedDetail(plan)) {
            var legacy = plans.legacyApplicationPlans(plan);
            if (!legacy.isEmpty())
                throw new IllegalArgumentException(
                        "旧单源已存在应用，请读取原计划 " + legacy.getFirst() + " 确认恢复，不能另建记录");
        }
    }

    private ContentEntity detail(Input input, Long recordId) {
        return detail(input, recordId, false);
    }

    private ContentEntity lockedDetail(Input input, Long recordId) {
        return detail(input, recordId, true);
    }

    private ContentEntity detail(Input input, Long recordId, boolean lock) {
        if (recordId == null || recordId <= 0 || recordId > Integer.MAX_VALUE)
            throw new IllegalArgumentException("详情草稿记录不存在，不能转成新增");
        var plan = input.plan();
        int menuId = Math.toIntExact(plan.menuId());
        var record =
                lock
                        ? articles.lockActiveArticleInSiteMenu(
                                plan.siteId(), menuId, recordId.intValue())
                        : articles.selectActiveArticleInSiteMenu(
                                plan.siteId(), menuId, recordId.intValue());
        if (record == null
                || record.getId() == null
                || !Objects.equals(record.getId().longValue(), recordId)
                || !Objects.equals(record.getSiteProjectId(), plan.siteId())
                || !Objects.equals(record.getClassid(), Math.toIntExact(plan.menuId())))
            throw new IllegalArgumentException("详情草稿记录不存在、已删除或不属于当前站点栏目；不能转成新增");
        if (!Objects.equals(record.getStatus(), ContentStatus.DRAFT.code()))
            throw new IllegalArgumentException("详情仅允许DRAFT草稿；待审、发布、驳回、归档及未知状态不支持此私有预览");
        return record;
    }

    private UiPackageInspectionService.Preview renderDetail(Input input, ContentEntity content) {
        var plan = input.plan();
        String rendered =
                renderer.renderControlledDetail(
                        plan.code(), input.compilation(), input.menu(), content);
        return isolation.isolateRendered(
                plan.code(), plan.packageVersion(), plan.sourcePath(), rendered);
    }

    private void validateDetailPages(Input input, ContentEntity content) {
        renderDetail(input, content);
        if (input.listCompilation() != null) renderList(input, content);
    }

    private UiPackageInspectionService.Preview renderList(Input input, ContentEntity content) {
        String html =
                renderer.renderControlledDetail(
                        input.plan().code(), input.listCompilation(), input.menu(), content);
        String path =
                input.plan().origins().stream()
                        .filter(origin -> "LIST".equals(origin.role()))
                        .findFirst()
                        .orElseThrow()
                        .sourcePath();
        return isolation.isolateRendered(
                input.plan().code(), input.plan().packageVersion(), path, html);
    }

    private void completeAfterCommit(Input input, Long recordId) {
        completeAfterCommit(input, recordId, "内容已保存，私有草稿可授权预览");
    }

    private void completeAfterCommit(Input input, Long recordId, String message) {
        completeAfterCommit(input, recordId, message, null);
    }

    private void completeAfterCommit(Input input, Long recordId, String message, Stored prepared) {
        var success =
                new Stored(
                        input.plan().id(),
                        input.compilation().html(),
                        "INITIALIZED",
                        message,
                        recordId,
                        prepared != null,
                        null);
        var failed =
                new Stored(
                        input.plan().id(),
                        input.compilation().html(),
                        "FAILED",
                        "数据库事务未提交，请检查原记录后重新确认",
                        recordId,
                        prepared != null,
                        null);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        completeState(success);
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) completeState(failed);
                    }

                    private void completeState(Stored state) {
                        if (prepared == null) persist(input, state);
                        else compareAndPersistExisting(input, prepared, state);
                    }
                });
    }

    private Stored read(TemplateFieldPlanService.Plan plan) {
        return read(plan.code(), TemplateFieldPlanService.applicationId(plan));
    }

    private void persist(Input input, Stored state) {
        persist(input.plan().code(), TemplateFieldPlanService.applicationId(input.plan()), state);
    }

    /** 同一状态文件的检查与写入使用稳定的旁路文件锁，替换状态文件时锁仍有效。 */
    private boolean compareAndPersistExisting(Input input, Stored expected, Stored next) {
        var plan = input.plan();
        String id = TemplateFieldPlanService.applicationId(plan);
        Path lockPath = file(plan.code(), id).resolveSibling(id + ".lock");
        synchronized (TextBindingDraftService.class) {
            try (var channel =
                            FileChannel.open(
                                    lockPath,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS);
                    var lock = channel.lock()) {
                if (!Objects.equals(read(plan.code(), id), expected)) return false;
                persist(plan.code(), id, next);
                return true;
            } catch (java.io.IOException error) {
                throw new IllegalStateException("私有草稿应用状态无法加锁", error);
            }
        }
    }

    private void checkState(Stored state, Input input) {
        if (state != null && !Objects.equals(state.planId(), input.plan().id()))
            throw new IllegalArgumentException(
                    "同一详情已有其他私有草稿应用，请读取原计划 " + state.planId() + " 恢复；不会新增或覆盖原内容");
        if (state != null
                && (!Set.of("PREPARED", "FAILED", "INITIALIZED")
                                .contains(String.valueOf(state.phase()))
                        || !Objects.equals(state.planId(), input.plan().id())
                        || !Objects.equals(state.html(), input.compilation().html())))
            throw new IllegalArgumentException("私有草稿文件与当前确认计划不一致，拒绝执行");
    }

    private Path file(String code, String id) {
        if (code == null
                || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || id == null
                || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("草稿标识无效");
        try {
            if (Files.isSymbolicLink(privateRoot)) throw new IllegalArgumentException("草稿根目录不能是链接");
            Files.createDirectories(privateRoot);
            Path real = privateRoot.toRealPath();
            Path project = real.resolve(code), directory = project.resolve("text-drafts");
            if (Files.isSymbolicLink(project) || Files.isSymbolicLink(directory))
                throw new IllegalArgumentException("草稿目录不能是链接");
            Files.createDirectories(directory);
            if (!directory.toRealPath().startsWith(real))
                throw new IllegalArgumentException("草稿目录越界");
            var file = directory.resolve(id + ".json");
            if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("草稿文件不能是链接");
            return file;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("私有草稿目录不可用", error);
        }
    }

    private Stored read(String code, String id) {
        Path file = file(code, id);
        if (!Files.exists(file)) return null;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(6 * 1024 * 1024 + 1);
            if (bytes.length > 6 * 1024 * 1024) throw new IllegalArgumentException("私有草稿文件超限");
            return json.readValue(bytes, Stored.class);
        } catch (java.io.IOException | tools.jackson.core.JacksonException error) {
            throw new IllegalArgumentException("私有草稿文件无法读取", error);
        }
    }

    private void persist(String code, String id, Stored state) {
        Path file = file(code, id);
        try {
            String serialized = json.writeValueAsString(state);
            if (serialized.length() > 6 * 1024 * 1024)
                throw new IllegalArgumentException("私有草稿文件超限");
            TemplateFileWriter.write(file.getParent(), file.getFileName().toString(), serialized);
        } catch (java.io.IOException | tools.jackson.core.JacksonException error) {
            throw new IllegalStateException("私有草稿状态保存失败；请检查内容是否已提交后重试确认", error);
        }
    }
}
