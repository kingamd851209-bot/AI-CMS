package com.xinhongru.content.model;

import static org.assertj.core.api.Assertions.*;

import com.alibaba.fastjson.JSON;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.entity.ContentEntity;

import org.junit.jupiter.api.Test;

import java.util.List;

class ContentModelCodecTest {
    @Test
    void readsExistingModelWithoutLosingEditorOptions() {
        var fields =
                ContentModelCodec.read(
                        "[{\"id\":7,\"fieldName\":\"title\",\"isOpen\":\"on\",\"isRequired\":\"1\",\"inputMultiple\":true,\"maxLength\":120,\"title\":\"标题\"}]");
        var restored = ContentModelCodec.read(ContentModelCodec.write(fields));
        assertThat(restored).usingRecursiveComparison().isEqualTo(fields);
        assertThat(restored.getFirst().getInputMultiple()).isTrue();
        assertThat(ContentFieldDefinition.enabled(restored.getFirst().getIsRequired())).isTrue();
        assertThat(ContentModelCodec.read(null)).isEmpty();
        assertThat(ContentModelCodec.read("null")).isEmpty();
    }

    @Test
    void rejectsInvalidAndDuplicateFieldNames() {
        var field = new ContentFieldDefinition().setFieldName("title");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContentModelCodec.write(List.of(field, field)));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                ContentModelCodec.write(
                                        List.of(
                                                new ContentFieldDefinition()
                                                        .setFieldName(
                                                                "id; DROP TABLE t_contents"))));
        assertThatIllegalArgumentException().isThrownBy(() -> ContentModelCodec.read("{broken"));
    }

    @Test
    void inheritedIdentifierRemainsVisibleToReflectionAndJson() throws Exception {
        var content = new ContentEntity().setId(21).setTitle("test");
        assertThat(content.isNew()).isFalse();
        assertThat(EntityFields.all(ContentEntity.class))
                .filteredOn(f -> f.getName().equals("id"))
                .hasSize(1);
        var id = EntityFields.find(ContentEntity.class, "id");
        id.setAccessible(true);
        assertThat(id.get(content)).isEqualTo(21);
        assertThat(JSON.parseObject(JSON.toJSONString(content)).getInteger("id")).isEqualTo(21);
        assertThat(JSON.parseObject(JSON.toJSONString(content))).doesNotContainKey("new");
        assertThat(new ContentEntity().setId(22).setTitle("test")).isNotEqualTo(content);
    }
}
