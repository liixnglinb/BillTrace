package com.voyra.billtrace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 归类规则与渠道识别的契约。其中一条专门锁住对外宣传的「110+ 条规则」。 */
public class RulesTest {

    @Test
    public void 规则条数支撑对外宣称的110条以上() {
        int n = Rules.MERCHANTS.length;
        assertTrue("README/下载页写的是「110+ 条」，实际 " + n + " 条", n >= 110);
        assertTrue("超过 120 说明文案该往上调", n < 130);
    }

    @Test
    public void 最长关键字优先命中() {
        assertEquals("canyin", Rules.classify("美团外卖", "")[0]);
        assertEquals("外卖", Rules.classify("美团外卖", "")[1]);
        assertEquals("meituan", Rules.classify("美团外卖", "")[2]);
        // 「美团单车」比「美团」长，必须落到交通而不是外卖
        assertEquals("jiaotong", Rules.classify("美团单车", "")[0]);
        assertEquals("共享单车", Rules.classify("美团单车", "")[1]);
    }

    @Test
    public void 未命中落到其他并带未分类() {
        String[] r = Rules.classify("某个没听过的店", "");
        assertEquals("qita", r[0]);
        assertEquals("未分类", r[1]);
        assertEquals("", r[2]);
    }

    @Test
    public void 未命中时用渠道图标兜底() {
        String[] r = Rules.classify("某个没听过的店", "alipay");
        assertEquals("qita", r[0]);
        assertEquals("alipay", r[2]);
    }

    @Test
    public void 工资不再硬套招商银行图标() {
        assertEquals("", Rules.classify("工资到账8500.00元", "")[2]);
        assertEquals("", Rules.classify("薪资发放", "")[2]);
    }

    @Test
    public void 饿了么不再引用缺失的图标文件() {
        assertEquals("", Rules.classify("饿了么订单", "")[2]);
    }

    @Test
    public void 渠道包名识别() {
        assertEquals("alipay", Rules.channelApp("com.eg.android.AlipayGphone"));
        assertEquals("wechat", Rules.channelApp("com.tencent.mm"));
        assertEquals("meituan", Rules.channelApp("com.sankuai.meituan"));
        assertEquals("jd", Rules.channelApp("com.jd.jrapp"));
        assertEquals("pdd", Rules.channelApp("com.xunmeng.pinduoduo"));
        assertEquals("didi", Rules.channelApp("com.sdu.didi.psnger"));
        assertEquals("amap", Rules.channelApp("com.autonavi.minimap"));
        assertEquals("cmb", Rules.channelApp("cmb.pb"));
        assertEquals("", Rules.channelApp("com.unknown.app"));
        assertEquals("", Rules.channelApp(null));
    }

    @Test
    public void 渠道中文名() {
        assertEquals("支付宝", Rules.channelName("com.eg.android.AlipayGphone"));
        assertEquals("微信", Rules.channelName("com.tencent.mm"));
        assertEquals("", Rules.channelName("com.unknown.app"));
    }

    @Test
    public void 商户抽取取最长命中() {
        assertEquals("美团外卖", Rules.merchant("【支付宝】支付成功35.80元，美团外卖"));
        assertEquals("星巴克", Rules.merchant("星巴克咖啡 30元"));
        assertEquals("", Rules.merchant("没有任何已知商户"));
        assertEquals("", Rules.merchant(null));
    }

    @Test
    public void 分类中文名覆盖全部界面分类() {
        assertEquals("餐饮", Rules.catName("canyin"));
        assertEquals("交通", Rules.catName("jiaotong"));
        assertEquals("购物", Rules.catName("gouwu"));
        assertEquals("居住", Rules.catName("juzhu"));
        assertEquals("娱乐", Rules.catName("yule"));
        assertEquals("医疗", Rules.catName("yiliao"));
        assertEquals("教育", Rules.catName("jiaoyu"));
        assertEquals("人情", Rules.catName("renqing"));
        assertEquals("金融", Rules.catName("jinrong"));
        assertEquals("其他", Rules.catName("qita"));
        // 导出用的名字必须能从规则表里全部覆盖到
        for (String[] row : Rules.MERCHANTS) {
            assertTrue(row[1] + " 没有中文名，导出 CSV 会露出英文 id",
                    !Rules.catName(row[1]).equals(row[1]));
        }
        assertEquals("未知分类原样返回，不抛异常", "weird", Rules.catName("weird"));
        assertEquals("", Rules.catName(null));
    }

    @Test
    public void 关键字匹配大小写不敏感() {
        // 通知正文里 "kfc"/"b站"/"steam" 这类小写写法以前匹配不上，会落到其他分类
        assertEquals("canyin", Rules.classify("kfc 套餐 35元", "")[0]);
        assertEquals("KFC", Rules.merchant("kfc 套餐 35元"));
        assertEquals("yule", Rules.classify("b站大会员续费", "")[0]);
        assertEquals("yule", Rules.classify("steam 游戏", "")[0]);
        assertEquals("yule", Rules.classify("qq音乐会员", "")[0]);
        assertEquals("canyin", Rules.classify("Food delivery 20元", "")[0]);
        assertEquals("jiaotong", Rules.classify("t3出行 12元", "")[0]);
    }

    @Test
    public void 每条规则的字段都完整合法() {
        for (String[] row : Rules.MERCHANTS) {
            assertEquals(row[0] + " 字段数不对", 4, row.length);
            assertTrue(row[0] + " 关键字为空", row[0] != null && !row[0].isEmpty());
            assertTrue(row[0] + " 分类为空", row[1] != null && !row[1].isEmpty());
            assertTrue(row[0] + " 子类为空", row[2] != null && !row[2].isEmpty());
        }
    }
}
