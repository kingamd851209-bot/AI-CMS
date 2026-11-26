package com.xinhongru.cms.content.record;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xinhongru.entity.*;
import com.xinhongru.mapper.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

class ContentRecordServiceTest {
    OneShotMapper menus;
    ContentMapper contents;
    ContentHistoryMapper history;
    UserMapper users;
    ContentRecordService service;

    @BeforeEach
    void setup() {
        menus = mock(OneShotMapper.class);
        contents = mock(ContentMapper.class);
        history = mock(ContentHistoryMapper.class);
        users = mock(UserMapper.class);
        service = new ContentRecordService(menus, contents, history, users);
        when(menus.lockFieldConfiguration(2L, 8L))
                .thenReturn(
                        new OneshotEntity()
                                .setId(8)
                                .setSiteProjectId(2L)
                                .setModelField(
                                        """
                                        [{"fieldName":"title","isOpen":"on","maxLength":10},{"fieldName":"content1","isOpen":"1"},{"fieldName":"sort","isOpen":"true"}]
                                        """));
    }

    @Test
    void groupAssignmentWritesOnlySelectedFieldAndKeepsHistory() {
        String field = "ext_a1234567890123456789012345678901";
        var draft =
                groupDraft(17, 3)
                        .setTitle("原有标题")
                        .setContent1("原有正文")
                        .setExtensionFields("{\"ext_b1234567890123456789012345678901\":\"其他值\"}");
        when(contents.lockActiveArticleInSiteMenu(2L, 8, 17)).thenReturn(draft);
        when(contents.assignDraftGroup(eq(2L), same(draft), isNull(), eq("2026"), eq(3)))
                .thenReturn(1);

        var result =
                service.assignDraftGroup(
                        2L,
                        8,
                        field,
                        "\u30002026\u00a0",
                        List.of(new ContentRecordService.GroupAssignmentRow(17, "17:3")),
                        null);

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(draft.getExtensionValues())
                .containsEntry(field, "2026")
                .containsEntry("ext_b1234567890123456789012345678901", "其他值");
        assertThat(draft.getStatus()).isZero();
        assertThat(draft.getTitle()).isEqualTo("原有标题");
        assertThat(draft.getContent1()).isEqualTo("原有正文");
        verify(contents, never()).updateArticleById(any());
        var audit = ArgumentCaptor.forClass(ContentHistoryEntity.class);
        verify(history).save(audit.capture());
        assertThat(audit.getValue().getComparison()).contains(field, "2026");
    }

    @Test
    void groupAssignmentRejectsStalePublishedAssignedAndForeignDraftsBeforeFirstWrite() {
        var first = groupDraft(17, 3);
        when(contents.lockActiveArticleInSiteMenu(2L, 8, 17)).thenReturn(first);
        var rows =
                List.of(
                        new ContentRecordService.GroupAssignmentRow(17, "17:3"),
                        new ContentRecordService.GroupAssignmentRow(18, "18:0"));

        for (ContentEntity conflict :
                new ContentEntity[] {
                    null,
                    groupDraft(18, 1),
                    groupDraft(18, 0).setStatus(1),
                    groupDraft(18, 0).setSingleLine2("已有分组")
                }) {
            when(contents.lockActiveArticleInSiteMenu(2L, 8, 18)).thenReturn(conflict);
            assertThatThrownBy(
                            () ->
                                    service.assignDraftGroup(
                                            2L, 8, "singleLine2", "2026", rows, null))
                    .isInstanceOf(ContentRecordService.DraftGroupConflict.class);
        }

        verify(contents, never()).assignDraftGroup(anyLong(), any(), any(), any(), anyInt());
        verifyNoInteractions(history);
        assertThat(first.getSingleLine2()).isNull();
    }

    @Test
    void groupAssignmentRejectsDuplicateAndReadOnlyFields() {
        groupDraft(17, null);
        var row = new ContentRecordService.GroupAssignmentRow(17, "17:0");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                service.assignDraftGroup(
                                        2L, 8, "singleLine2", "2026", List.of(row, row), null));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                service.assignDraftGroup(
                                        2L, 8, "singleLine3", "2026", List.of(row), null));
        verifyNoInteractions(contents, history);
    }

    private ContentEntity groupDraft(int id, Integer version) {
        when(menus.lockFieldConfiguration(2L, 8L))
                .thenReturn(
                        new OneshotEntity()
                                .setId(8)
                                .setSiteProjectId(2L)
                                .setModelField(
                                        """
                                        [
                                          {"fieldName":"singleLine2","fieldType":"SingleLine","isOpen":"1"},
                                          {"fieldName":"singleLine3","fieldType":"SingleLine","isOpen":"1","isReadOnly":"1"},
                                          {"fieldName":"ext_a1234567890123456789012345678901","title":"年份","fieldType":"SingleLine","isOpen":"1","maxLength":20}
                                        ]
                                        """));
        return new ContentEntity()
                .setId(id)
                .setClassid(8)
                .setSiteProjectId(2L)
                .setStatus(0)
                .setVersion(version);
    }

    ContentRecordService.SaveDraftCommand command(Long id, Map<String, Object> values) {
        return new ContentRecordService.SaveDraftCommand(2L, 8L, id, Map.of(), values, null);
    }

    @Test
    void createsOneRecordAndOneHistoryWithInheritedGeneratedIdentifier() {
        doAnswer(
                        i -> {
                            ((ContentEntity) i.getArgument(0)).setId(17);
                            return 1;
                        })
                .when(contents)
                .addArticle(any());
        var result =
                service.saveDraft(
                        command(
                                null,
                                Map.of("title", "标题", "content1", List.of("a", "b"), "sort", "3")));
        assertThat(result.recordId()).isEqualTo(17);
        var saved = ArgumentCaptor.forClass(ContentEntity.class);
        verify(contents).addArticle(saved.capture());
        assertThat(saved.getValue().getContent1()).isEqualTo(",a,b,");
        assertThat(saved.getValue().getSort()).isEqualTo(3);
        assertThat(saved.getValue().getStatus()).isZero();
        assertThat(saved.getValue().getSiteProjectId()).isEqualTo(2);
        verify(contents).assignContentCodeToOwnId(17);
        verify(history).save(any());
    }

    @Test
    void updatesExistingScopedRecordWithoutDuplicatingAndRetainsWorkflow() {
        when(contents.lockActiveArticleInSiteMenu(2L, 8, 17))
                .thenReturn(
                        new ContentEntity()
                                .setId(17)
                                .setClassid(8)
                                .setSiteProjectId(2L)
                                .setStatus(1)
                                .setTitle("旧标题"));
        assertThat(service.saveDraft(command(17L, Map.of("title", "新标题"))).workflowStatus())
                .isEqualTo("PUBLISHED");
        verify(contents, never()).addArticle(any());
        var saved = ArgumentCaptor.forClass(ContentEntity.class);
        verify(contents).updateArticleById(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("新标题");
        assertThat(saved.getValue().getStatus()).isEqualTo(1);
        var audit = ArgumentCaptor.forClass(ContentHistoryEntity.class);
        verify(history).save(audit.capture());
        assertThat(audit.getValue().getComparison()).contains("旧标题", "新标题");
    }

    @Test
    void rejectsForeignRecordUnknownAndProtectedFieldsBeforeWrites() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.saveDraft(command(999L, Map.of("title", "x"))));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                service.saveDraft(
                                        new ContentRecordService.SaveDraftCommand(
                                                3L, 8L, null, Map.of(), Map.of(), null)));
        for (String field : List.of("id", "siteProjectId", "classid", "status", "notAField"))
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.saveDraft(command(null, Map.of(field, "1"))));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                service.saveDraft(
                                        command(null, Map.of("title", "this title is too long"))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.saveDraft(command(null, Map.of("sort", "invalid"))));
        verify(contents, never()).addArticle(any());
        verifyNoInteractions(history);
    }

    @Test
    void defaultsCreateTimeWhenSubmittedCreateTimeIsBlank() {
        // 模型含 createTime 字段（发布时间），新增时提交空值：不应把 create_time 置空（该列 NOT NULL 无默认值）
        when(menus.lockFieldConfiguration(2L, 9L))
                .thenReturn(
                        new OneshotEntity()
                                .setId(9)
                                .setSiteProjectId(2L)
                                .setModelField(
                                        """
                                        [{"fieldName":"title","isOpen":"on"},{"fieldName":"createTime","isOpen":"on"}]
                                        """));
        doAnswer(
                        i -> {
                            ((ContentEntity) i.getArgument(0)).setId(21);
                            return 1;
                        })
                .when(contents)
                .addArticle(any());

        service.saveDraft(
                new ContentRecordService.SaveDraftCommand(
                        2L, 9L, null, Map.of(), Map.of("title", "标题", "createTime", ""), null));

        var saved = ArgumentCaptor.forClass(ContentEntity.class);
        verify(contents).addArticle(saved.capture());
        assertThat(saved.getValue().getCreateTime()).isNotNull();
    }
}
