package com.xinhongru.ai.config;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_ai_provider_config")
public class AiProviderConfigEntity extends LongEntity<AiProviderConfigEntity> {
    private static final long serialVersionUID = 1L;

    private String providerCode;

    private String providerName;

    private String usageType;

    private String modelName;

    private String baseUrl;

    private String apiKeyCipher;

    private String apiKeyNonce;

    private Integer enabled;

    private Integer timeoutMs;

    private Integer dailyBudgetCents;

    private Integer maxRetries;

    private Integer dailyTokenBudget;

    private Integer monthlyCostBudgetCents;

    private String promptVersion;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic private Integer deleted;

    @Override
    public String toString() {
        return "AiProviderConfigEntity{"
                + "id="
                + id
                + ", providerCode='"
                + providerCode
                + '\''
                + ", providerName='"
                + providerName
                + '\''
                + ", usageType='"
                + usageType
                + '\''
                + ", modelName='"
                + modelName
                + '\''
                + ", baseUrl='"
                + baseUrl
                + '\''
                + ", hasApiKey="
                + (apiKeyCipher != null && !apiKeyCipher.isBlank())
                + ", enabled="
                + enabled
                + ", timeoutMs="
                + timeoutMs
                + ", dailyBudgetCents="
                + dailyBudgetCents
                + ", maxRetries="
                + maxRetries
                + ", dailyTokenBudget="
                + dailyTokenBudget
                + ", monthlyCostBudgetCents="
                + monthlyCostBudgetCents
                + ", promptVersion='"
                + promptVersion
                + '\''
                + ", deleted="
                + deleted
                + '}';
    }
}
