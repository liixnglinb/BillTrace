package com.voyra.billtrace;

import java.util.Locale;

/**
 * 商户关键字 -> 分类/子类/图标。命中越长的关键字优先，用户改过的分类会覆盖这里（见 TxnStore）。
 * 表里没有的商户落到 qita，并且标记为待确认，让用户改一次后永久生效。
 */
public class Rules {

    public static final String[][] MERCHANTS = {
        // 餐饮
        {"美团外卖", "canyin", "外卖", "meituan"},
        {"美团", "canyin", "外卖", "meituan"},
        {"饿了么", "canyin", "外卖", "ele"},
        {"肯德基", "canyin", "堂食", "kfc"},
        {"KFC", "canyin", "堂食", "kfc"},
        {"麦当劳", "canyin", "堂食", ""},
        {"汉堡王", "canyin", "堂食", ""},
        {"海底捞", "canyin", "堂食", ""},
        {"瑞幸", "canyin", "咖啡奶茶", "luckin"},
        {"星巴克", "canyin", "咖啡奶茶", "starbucks"},
        {"喜茶", "canyin", "咖啡奶茶", ""},
        {"奈雪", "canyin", "咖啡奶茶", ""},
        {"蜜雪冰城", "canyin", "咖啡奶茶", ""},
        {"茶百道", "canyin", "咖啡奶茶", ""},
        {"古茗", "canyin", "咖啡奶茶", ""},
        {"沪上阿姨", "canyin", "咖啡奶茶", ""},
        {"库迪", "canyin", "咖啡奶茶", ""},
        {"沙县", "canyin", "堂食", ""},
        {"兰州拉面", "canyin", "堂食", ""},
        {"餐饮", "canyin", "堂食", ""},
        {"food", "canyin", "外卖", ""},
        // 交通
        {"滴滴", "jiaotong", "打车", "didi"},
        {"高德", "jiaotong", "打车", "amap"},
        {"曹操出行", "jiaotong", "打车", ""},
        {"T3出行", "jiaotong", "打车", ""},
        {"花小猪", "jiaotong", "打车", ""},
        {"哈啰", "jiaotong", "共享单车", ""},
        {"青桔", "jiaotong", "共享单车", ""},
        {"美团单车", "jiaotong", "共享单车", "meituan"},
        {"地铁", "jiaotong", "公交地铁", ""},
        {"公交", "jiaotong", "公交地铁", ""},
        {"一卡通", "jiaotong", "公交地铁", ""},
        {"12306", "jiaotong", "火车", ""},
        {"铁路", "jiaotong", "火车", ""},
        {"航空", "jiaotong", "机票", ""},
        {"加油", "jiaotong", "加油", ""},
        {"中石化", "jiaotong", "加油", ""},
        {"中石油", "jiaotong", "加油", ""},
        {"停车", "jiaotong", "停车", ""},
        {"高速", "jiaotong", "过路费", ""},
        // 购物
        {"淘宝", "gouwu", "网购", "taobao"},
        {"天猫", "gouwu", "网购", "taobao"},
        {"京东", "gouwu", "网购", "jd"},
        {"拼多多", "gouwu", "网购", "pdd"},
        {"唯品会", "gouwu", "服饰", ""},
        {"得物", "gouwu", "服饰", ""},
        {"苏宁", "gouwu", "数码", ""},
        {"小红书", "gouwu", "网购", ""},
        {"抖音", "gouwu", "网购", ""},
        {"快手", "gouwu", "网购", ""},
        {"盒马", "gouwu", "生鲜", ""},
        {"永辉", "gouwu", "生鲜", ""},
        {"山姆", "gouwu", "商超", ""},
        {"沃尔玛", "gouwu", "商超", ""},
        {"全家", "gouwu", "便利店", ""},
        {"罗森", "gouwu", "便利店", ""},
        {"711", "gouwu", "便利店", ""},
        {"美宜佳", "gouwu", "便利店", ""},
        // 居住
        {"电费", "juzhu", "水电燃气", ""},
        {"水费", "juzhu", "水电燃气", ""},
        {"燃气", "juzhu", "水电燃气", ""},
        {"国家电网", "juzhu", "水电燃气", ""},
        {"物业", "juzhu", "物业", ""},
        {"房租", "juzhu", "房租", ""},
        {"宽带", "juzhu", "宽带", ""},
        {"中国移动", "juzhu", "话费", ""},
        {"中国联通", "juzhu", "话费", ""},
        {"中国电信", "juzhu", "话费", ""},
        {"话费", "juzhu", "话费", ""},
        // 娱乐
        {"网易云", "yule", "音乐会员", "music163"},
        {"QQ音乐", "yule", "音乐会员", ""},
        {"腾讯视频", "yule", "视频会员", "tvideo"},
        {"爱奇艺", "yule", "视频会员", ""},
        {"优酷", "yule", "视频会员", ""},
        {"芒果", "yule", "视频会员", ""},
        {"哔哩哔哩", "yule", "视频会员", ""},
        {"B站", "yule", "视频会员", ""},
        {"微信读书", "yule", "阅读", ""},
        {"得到", "yule", "阅读", ""},
        {"知乎", "yule", "阅读", ""},
        {"电影", "yule", "电影", ""},
        {"影城", "yule", "电影", ""},
        {"KTV", "yule", "娱乐", ""},
        {"王者荣耀", "yule", "游戏", ""},
        {"和平精英", "yule", "游戏", ""},
        {"Steam", "yule", "游戏", ""},
        // 医疗
        {"医院", "yiliao", "就医", ""},
        {"药房", "yiliao", "药品", ""},
        {"大药房", "yiliao", "药品", ""},
        {"体检", "yiliao", "体检", ""},
        {"诊所", "yiliao", "就医", ""},
        // 教育
        {"学费", "jiaoyu", "学费", ""},
        {"培训", "jiaoyu", "培训", ""},
        {"网校", "jiaoyu", "培训", ""},
        {"书店", "jiaoyu", "书籍", ""},
        {"当当", "jiaoyu", "书籍", ""},
        // 人情
        {"红包", "renqing", "红包", ""},
        {"转账", "renqing", "转账", ""},
        {"礼金", "renqing", "礼金", ""},
        // 金融
        {"工资", "jinrong", "工资", "cmb"},
        {"薪资", "jinrong", "工资", "cmb"},
        {"报销", "jinrong", "报销", ""},
        {"退款", "jinrong", "退款", ""},
        {"利息", "jinrong", "利息", ""},
        {"花呗", "jinrong", "还款", "alipay"},
        {"借呗", "jinrong", "还款", "alipay"},
        {"白条", "jinrong", "还款", "jd"},
        {"信用卡还款", "jinrong", "还款", ""},
        {"保险", "jinrong", "保险", ""},
        {"基金", "jinrong", "理财", ""},
        {"理财", "jinrong", "理财", ""},
    };

    /** 渠道包名 -> 图标 key，商户识别不出来时用渠道图标兜底。 */
    public static String channelApp(String pkg) {
        if (pkg == null) return "";
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("alipay") || p.contains("eg.android")) return "alipay";
        if (p.contains("tencent.mm")) return "wechat";
        if (p.contains("unionpay") || p.contains("cloudquickpass")) return "alipay";
        if (p.contains("cmb")) return "cmb";
        if (p.contains("icbc")) return "cmb";
        if (p.contains("ccb")) return "cmb";
        if (p.contains("abchina") || p.contains("bankabc")) return "cmb";
        if (p.contains("boc") || p.contains("bankofchina")) return "cmb";
        if (p.contains("bankcomm")) return "cmb";
        if (p.contains("spdb") || p.contains("spdbccc")) return "cmb";
        if (p.contains("psbc")) return "cmb";
        if (p.contains("cgbchina")) return "cmb";
        if (p.contains("citic")) return "cmb";
        if (p.contains("pingan")) return "cmb";
        if (p.contains("meituan")) return "meituan";
        if (p.contains("jingdong") || p.contains("jd")) return "jd";
        if (p.contains("taobao") || p.contains("tmall")) return "taobao";
        if (p.contains("xunmeng") || p.contains("pinduoduo")) return "pdd";
        if (p.contains("didi")) return "didi";
        if (p.contains("autonavi") || p.contains("amap")) return "amap";
        return "";
    }

    /** 渠道包名 -> 账户名，用于展示。 */
    public static String channelName(String pkg) {
        String app = channelApp(pkg);
        if ("alipay".equals(app)) return "支付宝";
        if ("wechat".equals(app)) return "微信";
        if ("meituan".equals(app)) return "美团";
        if ("jd".equals(app)) return "京东";
        if ("taobao".equals(app)) return "淘宝";
        if ("pdd".equals(app)) return "拼多多";
        if ("didi".equals(app)) return "滴滴";
        if ("amap".equals(app)) return "高德";
        if ("cmb".equals(app)) return "银行";
        return "";
    }

    /** 返回 {category, sub, iconKey}；未命中返回 qita + 渠道兜底图标。 */
    public static String[] classify(String text, String channelApp) {
        String hitCat = null, hitSub = null, hitIcon = null;
        int hitLen = 0;
        if (text != null) {
            for (String[] row : MERCHANTS) {
                String kw = row[0];
                if (text.contains(kw) && kw.length() > hitLen) {
                    hitLen = kw.length();
                    hitCat = row[1];
                    hitSub = row[2];
                    hitIcon = row[3];
                }
            }
        }
        if (hitCat == null) hitCat = "qita";
        if (hitSub == null) hitSub = "未分类";
        if (hitIcon == null || hitIcon.isEmpty()) hitIcon = channelApp == null ? "" : channelApp;
        return new String[]{hitCat, hitSub, hitIcon};
    }

    /** 从原始文本里尽量挖出商户名，挖不到返回空串。 */
    public static String merchant(String text) {
        if (text == null) return "";
        String best = "";
        int bestLen = 0;
        for (String[] row : MERCHANTS) {
            String kw = row[0];
            if (text.contains(kw) && kw.length() > bestLen) {
                bestLen = kw.length();
                best = kw;
            }
        }
        return best;
    }
}
