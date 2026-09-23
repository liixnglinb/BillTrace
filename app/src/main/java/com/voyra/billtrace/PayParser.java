package com.voyra.billtrace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一段通知/短信文本解析成一条账目。识别不出来就返回 null，宁可不记也不乱记。
 * 只在金额、方向、交易词三者都成立时才认账，避免把验证码、账单提醒、优惠券当成消费。
 */
public class PayParser {

    private static final Pattern P_YUAN = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元");
    private static final Pattern P_SIGN = Pattern.compile("[¥￥]\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    private static final Pattern P_RMB = Pattern.compile("(?:人民币|RMB|rmb)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    private static final Pattern P_TAIL = Pattern.compile("(?:尾号|末四位|后四位|尾数为)\\s*([0-9]{4})");
    private static final Pattern P_TAIL2 = Pattern.compile("\\*{2,}\\s*([0-9]{4})");

    private static final String[] INCOME = {"到账", "入账", "收入", "收款", "退款", "收到", "转入", "存入", "工资", "薪资", "报销", "退回", "利息", "分红", "返现", "已退款"};
    private static final String[] SPEND = {"支付", "付款", "消费", "支出", "扣款", "转出", "已付", "交易成功", "缴费", "缴纳", "购买", "还款", "续费", "订阅"};
    private static final String[] REJECT = {"验证码", "校验码", "动态码", "应还", "最低还款", "账单已出", "到期还款", "优惠券", "立减", "满减", "抽奖", "中奖", "积分", "余额不足", "交易失败", "支付失败", "未成功", "已取消", "退款失败", "理财有风险"};
    private static final String[] STRONG = {"支付成功", "付款成功", "交易成功", "已支付", "已付款", "消费", "扣款", "到账", "入账", "收款", "退款成功", "已退款"};
    private static final String[] BALANCE_HINT = {"余额", "额度", "欠款", "剩余", "可用", "限额", "积分"};

    private static final Map<String, String> BANK_BY_SENDER = new HashMap<String, String>();
    static {
        BANK_BY_SENDER.put("95555", "招商银行");
        BANK_BY_SENDER.put("95588", "工商银行");
        BANK_BY_SENDER.put("95533", "建设银行");
        BANK_BY_SENDER.put("95599", "农业银行");
        BANK_BY_SENDER.put("95566", "中国银行");
        BANK_BY_SENDER.put("95559", "交通银行");
        BANK_BY_SENDER.put("95580", "邮储银行");
        BANK_BY_SENDER.put("95528", "浦发银行");
        BANK_BY_SENDER.put("95558", "中信银行");
        BANK_BY_SENDER.put("95568", "民生银行");
        BANK_BY_SENDER.put("95561", "兴业银行");
        BANK_BY_SENDER.put("95595", "光大银行");
        BANK_BY_SENDER.put("95511", "平安银行");
        BANK_BY_SENDER.put("95577", "华夏银行");
        BANK_BY_SENDER.put("95508", "广发银行");
    }
    private static final String[] BANK_NAMES = {"招商银行", "工商银行", "建设银行", "农业银行", "中国银行", "交通银行", "邮储银行", "浦发银行", "中信银行", "民生银行", "兴业银行", "光大银行", "平安银行", "华夏银行", "广发银行", "北京银行", "上海银行", "宁波银行", "江苏银行"};

    /** 主入口。pkg 传通知的包名，短信传发送号码。when<=0 用当前时间。 */
    public static Txn parse(String text, String pkg, String source, long when) {
        if (text == null) return null;
        String t = norm(text);
        if (t.length() < 4) return null;
        if (!looksLikeTxn(t)) return null;

        double amt = findAmount(t);
        if (amt <= 0 || amt > 9999999) return null;

        boolean income = isIncome(t);
        String merchant = Rules.merchant(t);
        if (merchant.isEmpty()) merchant = merchantByPattern(t);

        String channelApp = Rules.channelApp(pkg);
        String[] cls = Rules.classify(t, channelApp);
        String icon = cls[2];
        if (icon == null || icon.isEmpty()) icon = channelApp;

        Txn x = new Txn();
        x.timeMillis = when > 0 ? when : System.currentTimeMillis();
        x.amount = income ? amt : -amt;
        x.merchant = merchant.isEmpty() ? fallbackName(pkg, income) : merchant;
        x.category = cls[0];
        x.sub = cls[1];
        x.app = icon == null ? "" : icon;
        x.source = source;
        x.account = account(t, pkg);
        x.raw = t.length() > 300 ? t.substring(0, 300) : t;
        x.confidence = confidence(t, merchant, income);
        x.confirmed = ("qita".equals(x.category) && merchant.isEmpty()) ? 0 : 1;
        return x;
    }

    /** 全角转半角、压缩空白。 */
    public static String norm(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF10 && c <= 0xFF19) sb.append((char) (c - 0xFF10 + '0'));
            else if (c == 0xFF0C) sb.append(',');
            else if (c == 0xFF0E) sb.append('.');
            else if (c == 0x3000) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static boolean hasStrong(String t) {
        for (String k : STRONG) if (t.contains(k)) return true;
        return false;
    }

    private static boolean looksLikeTxn(String t) {
        for (String k : REJECT) if (t.contains(k) && !hasStrong(t)) return false;
        for (String k : INCOME) if (t.contains(k)) return true;
        for (String k : SPEND) if (t.contains(k)) return true;
        return false;
    }

    private static boolean isIncome(String t) {
        for (String k : INCOME) if (t.contains(k)) return true;
        return false;
    }

    private static void collect(Pattern p, String t, List<int[]> spans, List<Double> vals) {
        Matcher m = p.matcher(t);
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1).replace(",", ""));
                if (v > 0) {
                    spans.add(new int[]{m.start(1), m.end(1)});
                    vals.add(v);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 依次按 元 / ¥ / 人民币 找金额，跳过余额、额度这类干扰项，取最靠前的一个。 */
    private static double findAmount(String t) {
        Pattern[] order = {P_YUAN, P_SIGN, P_RMB};
        for (Pattern p : order) {
            List<int[]> spans = new ArrayList<int[]>();
            List<Double> vals = new ArrayList<Double>();
            collect(p, t, spans, vals);
            double best = 0;
            int bestPos = Integer.MAX_VALUE;
            for (int i = 0; i < spans.size(); i++) {
                int s = spans.get(i)[0];
                String pre = t.substring(Math.max(0, s - 8), s);
                boolean balance = false;
                for (String h : BALANCE_HINT) if (pre.contains(h)) { balance = true; break; }
                if (balance) continue;
                if (s < bestPos) { bestPos = s; best = vals.get(i); }
            }
            if (best > 0) return best;
        }
        return 0;
    }

    private static String merchantByPattern(String t) {
        String[] pats = {"向([^，。；\\s]{2,14}?)支付", "在([^，。；\\s]{2,14}?)(?:消费|支付|购买|充值)", "付款给([^，。；\\s]{2,14})", "转给([^，。；\\s]{2,14})", "收款方[:：]?\\s*([^，。；\\s]{2,14})"};
        for (String p : pats) {
            Matcher m = Pattern.compile(p).matcher(t);
            if (m.find()) {
                String s = m.group(1).trim();
                if (s.length() >= 2 && !s.contains("银行") && !s.contains("尾号")) return s;
            }
        }
        return "";
    }

    private static String fallbackName(String pkg, boolean income) {
        String cn = Rules.channelName(pkg);
        String bank = bankName("", pkg);
        String who = !cn.isEmpty() ? cn : bank;
        if (who.isEmpty()) who = "未知来源";
        return who + (income ? "收款" : "消费");
    }

    private static String bankName(String t, String pkg) {
        if (pkg != null) {
            String b = BANK_BY_SENDER.get(pkg.trim());
            if (b != null) return b;
        }
        for (String b : BANK_NAMES) if (t != null && t.contains(b)) return b;
        return "";
    }

    private static String tail(String t) {
        Matcher m = P_TAIL.matcher(t);
        if (m.find()) return m.group(1);
        Matcher m2 = P_TAIL2.matcher(t);
        if (m2.find()) return m2.group(1);
        return "";
    }

    private static String account(String t, String pkg) {
        String bank = bankName(t, pkg);
        String tl = tail(t);
        if (!bank.isEmpty() && !tl.isEmpty()) return bank + "(" + tl + ")";
        if (!bank.isEmpty()) return bank;
        if (!tl.isEmpty()) return "尾号" + tl;
        String cn = Rules.channelName(pkg);
        return cn;
    }

    private static int confidence(String t, String merchant, boolean income) {
        int c = 72;
        if (!merchant.isEmpty()) c += 14;
        if (P_YUAN.matcher(t).find()) c += 8;
        if (income && t.contains("到账")) c += 6;
        if (t.contains("支付成功") || t.contains("交易成功") || t.contains("已支付")) c += 6;
        if (c > 99) c = 99;
        return c;
    }
}
