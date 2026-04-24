package net.eca.util.bossshow;

//触发类型
public sealed interface Trigger {
    String type();

    //i18n translation key（按钮上显示用）
    default String translationKey() {
        return "gui.eca.bossshow.editor.trigger." + type();
    }

    //范围触发：玩家进入目标实体 radius 范围内且未看过时自动播放
    record Range(double effectRadius) implements Trigger {
        @Override public String type() { return "range"; }
    }

    //自定义触发：仅通过 EcaAPI.launchBossShowEvent(eventName,...) 匹配 eventName 启动
    //eventName 为空串表示未设置（编辑器里也不会命中任何 API 调用）
    record Custom(String eventName) implements Trigger {
        public Custom {
            if (eventName == null) eventName = "";
        }
        @Override public String type() { return "custom"; }
    }
}
