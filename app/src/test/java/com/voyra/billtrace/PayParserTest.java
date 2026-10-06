package com.voyra.billtrace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 解析器的核心契约：宁可不记，也不乱记。这些用例锁住的是产品最要命的行为
 * ——把验证码当消费、把余额当支出、把方向记反，都会直接毁掉账本的可信度。
 */
public class PayParserTest {

    private Txn parse(String text, String pkg) {
        return PayParser.parse(text, pkg, "短信", 1700000000000L);
    }

    @Test
    public void 支出通知_金额取负并识别商户() {
        Txn t = parse("【支付宝】支付成功35.80元，美团外卖", "com.eg.android.AlipayGphone");
        assertNotNull(t);
        assertEquals(-35.80, t.amount, 0.001);
        assertEquals("美团外卖", t.merchant);
        assertEquals("canyin", t.category);
        assertEquals("meituan", t.app);
        assertEquals("短信", t.source);
        assertEquals(1700000000000L, t.timeMillis);
        assertEquals("关键字表命中且置信度达标，应当自动确认", 1, t.confirmed);
    }

    @Test
    public void 工资到账_金额取正且不再套用招行图标() {
        Txn t = parse("【工商银行】工资到账8500.00元", "95588");
        assertNotNull(t);
        assertEquals(8500.00, t.amount, 0.001);
        assertEquals("jinrong", t.category);
        assertEquals("工资", t.merchant);
        assertEquals("渠道未知时不该硬套某家银行的图标", "", t.app);
    }

    @Test
    public void 余额干扰项_只取真实交易额() {
        Txn t = parse("账户余额9999.00元，本次消费35.00元", "95588");
        assertNotNull(t);
        assertEquals("余额那一段必须被跳过", -35.00, t.amount, 0.001);
    }

    @Test
    public void 验证码短信_一律不记() {
        assertNull(parse("您的验证码是123456，请勿泄露给他人", "95588"));
    }

    @Test
    public void 验证码里混入退款词_仍然不记() {
        assertNull(parse("您正在申请退款，验证码123456", "95588"));
    }

    @Test
    public void 没有交易词_不记() {
        assertNull(parse("今日天气晴，气温 25 摄氏度", "95588"));
    }

    @Test
    public void 超出上限的大额_不记() {
        assertNull(parse("消费10000000.00元", "95588"));
        assertNotNull("刚好在上限内应当放行", parse("消费9999999.00元", "95588"));
    }

    @Test
    public void 全角数字与小数点_归一化后正确解析() {
        Txn t = parse("支付成功１２３．５０元", "95588");
        assertNotNull(t);
        assertEquals(-123.50, t.amount, 0.001);
    }

    @Test
    public void 千分位金额_解析正确() {
        Txn t = parse("消费12,345元", "95588");
        assertNotNull(t);
        assertEquals(-12345.0, t.amount, 0.001);
    }

    @Test
    public void 认不出商户_进待确认并给兜底名() {
        Txn t = parse("消费100.00元", "");
        assertNotNull(t);
        assertEquals("qita", t.category);
        assertEquals("未知来源消费", t.merchant);
        assertEquals("认不出来的必须让人复核", 0, t.confirmed);
    }

    @Test
    public void 正则抠出的商户名_不得跳过人工确认() {
        Txn t = parse("在某某小铺消费100.00元", "");
        assertNotNull(t);
        assertEquals("某某小铺", t.merchant);
        assertEquals("置信度虽然高，但商户不是关键字表命中的，仍要确认", 0, t.confirmed);
    }

    @Test
    public void 尾号与银行合成账户名() {
        Txn t = parse("【工商银行】您尾号1234的储蓄卡消费35.00元", "95588");
        assertNotNull(t);
        assertEquals("工商银行(1234)", t.account);
    }

    @Test
    public void 原始文本截断到300字符() {
        StringBuilder sb = new StringBuilder("消费1.00元 ");
        while (sb.length() <= 320) sb.append('长');
        Txn t = parse(sb.toString(), "95588");
        assertNotNull(t);
        assertTrue(t.raw.length() <= 300);
    }

    @Test
    public void 空输入与超短输入安全() {
        assertNull(parse(null, "95588"));
        assertNull(parse("", "95588"));
        assertNull(parse("付", "95588"));
    }

    @Test
    public void 全角归一化函数本身() {
        assertEquals("123.50 元", PayParser.norm("１２３．５０　元"));
    }
}
