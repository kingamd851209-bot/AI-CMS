package com.xinhongru.ai.admin;

import com.xinhongru.entity.UserEntity;
import com.xinhongru.utils.IconClassUtils;

import org.springframework.stereotype.Component;

import java.time.Year;

@Component
public class AdminTemplateSupport {
    public boolean shouldExposeSmartMenus(UserEntity userEntity) {
        return SmartAdminMenuSupport.shouldExposeSmartMenus(userEntity);
    }

    public boolean canAccessSmartMenu(UserEntity userEntity, int menuId) {
        return SmartAdminMenuSupport.canAccessSmartMenu(userEntity, menuId);
    }

    public int getCmsMenuManagementId() {
        return SmartAdminMenuSupport.CMS_MENU_MANAGEMENT_ID;
    }

    public int getAiConfigId() {
        return SmartAdminMenuSupport.AI_CONFIG_ID;
    }

    public String getDefaultIconClass() {
        return IconClassUtils.getIconClass();
    }

    public int getCurrentYear() {
        return Year.now().getValue();
    }
}
