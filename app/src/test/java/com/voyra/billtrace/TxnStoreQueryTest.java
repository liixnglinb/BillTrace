package com.voyra.billtrace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 检索语句是纯字符串拼装，不碰数据库，可以在 JVM 侧直接断言。
 * 这批用例盯的是：条件之间必须是 OR（否则搜"餐饮"只出商户名带餐饮的账）、
 * 通配符必须转义（否则搜 % 等于全表）、占位符数量必须与绑定参数一一对应
 * （错位了不报错，只会静默搜出别的数据），以及用户输入绝不能进 SQL 文本。
 */
public class TxnStoreQueryTest {

    @Test
    public void 通配符和转义符被中和() {
        assertEquals("a\\%b", TxnStore.escapeLike("a%b"));
        assertEquals("a\\_b", TxnStore.escapeLike("a_b"));
        assertEquals("a\\\\b", TxnStore.escapeLike("a\\b"));
        assertEquals("", TxnStore.escapeLike(null));
    }

    @Test
    public void 商户匹配串是转义后带前后通配() {
        TxnStore.Sql q = TxnStore.buildSearch("a%b", null, null, 500);
        assertNotNull(q);
        assertEquals("%a\\%b%", q.args[0]);
    }

    @Test
    public void 三类条件之间是OR不是AND() {
        TxnStore.Sql q = TxnStore.buildSearch("美团", new String[]{"canyin", "jiaotong"}, 35.8, 500);
        // 只断言"含 OR"会被商户组自己内部的 OR 蒙过去，必须断到组与组之间的那个连接符
        assertTrue("商户/分类/金额必须任一命中：" + q.sql,
                q.sql.contains("') OR category IN (?,?) OR ROUND(ABS(amount), 2) = ?"));
        assertFalse(q.sql.contains(") AND category"));
        assertFalse(q.sql.contains(" AND ROUND"));
    }

    @Test
    public void 占位符数量与绑定参数一一对应() {
        TxnStore.Sql q = TxnStore.buildSearch("美团", new String[]{"canyin", "jiaotong", "qita"}, 12.5, 500);
        assertNotNull(q);
        int marks = 0;
        for (int i = 0; i < q.sql.length(); i++) if (q.sql.charAt(i) == '?') marks++;
        // 商户组 3 个 + 分类 3 个 + 金额 1 个 + LIMIT 1 个
        assertEquals(8, marks);
        assertEquals(marks, q.args.length);
        assertEquals("500", q.args[q.args.length - 1]);
    }

    @Test
    public void 单条件时IN列表不带多余逗号() {
        TxnStore.Sql q = TxnStore.buildSearch(null, new String[]{"canyin"}, null, 10);
        assertNotNull(q);
        assertTrue(q.sql.contains("category IN (?)"));
        assertEquals(2, q.args.length);
        assertEquals("canyin", q.args[0]);
    }

    @Test
    public void 没有任何条件时返回null而不是全表() {
        // 返回全表会让"空搜索词"变成一次无上限全量导出
        assertNull(TxnStore.buildSearch("", null, null, 500));
        assertNull(TxnStore.buildSearch(null, new String[0], null, 500));
    }

    @Test
    public void 金额按绝对值匹配不分收支() {
        TxnStore.Sql q = TxnStore.buildSearch(null, null, 35.8, 500);
        assertNotNull(q);
        assertTrue(q.sql.contains("ROUND(ABS(amount), 2) = ?"));
        assertEquals("35.8", q.args[0]);
    }

    @Test
    public void 用户输入只进绑定参数不进SQL文本() {
        String evil = "'; DROP TABLE txns; --%_";
        TxnStore.Sql q = TxnStore.buildSearch(evil, null, null, 500);
        assertNotNull(q);
        assertFalse("拼接进语句文本的只能是占位符：" + q.sql, q.sql.contains("DROP"));
        assertFalse(q.sql.contains("';"));
        assertTrue(q.args[0].contains("\\%") && q.args[0].contains("\\_"));
    }

    @Test
    public void 结果排除软删除并按时间倒序() {
        TxnStore.Sql q = TxnStore.buildSearch("美团", null, null, 500);
        assertNotNull(q);
        assertTrue(q.sql.contains("deleted=0"));
        // id 是键集分页的二级排序键，缺了它同一毫秒的两笔会翻错页
        assertTrue(q.sql.contains("ORDER BY ts DESC, id DESC"));
    }

    @Test
    public void 第一页与检索用的是同一套列顺序() {
        // read(Cursor) 按下标取值，三处 SELECT 的列序一旦不一致，字段会整体错位且不报错
        String cols = "id, ts, amount, merchant, category, sub, app, source, account, raw, confidence, confirmed";
        assertTrue(TxnStore.buildList(200).sql.contains("SELECT " + cols + " FROM txns"));
        assertTrue(TxnStore.buildListAfter(1L, 1L, 200).sql.contains("SELECT " + cols + " FROM txns"));
        assertTrue(TxnStore.buildSearch("x", null, null, 200).sql.contains("SELECT " + cols + " FROM txns"));
    }

    @Test
    public void 第一页只有LIMIT一个绑定参数() {
        TxnStore.Sql q = TxnStore.buildList(200);
        assertEquals(1, q.args.length);
        assertEquals("200", q.args[0]);
        assertTrue(q.sql.contains("WHERE deleted=0"));
        assertFalse("第一页不该带游标条件：" + q.sql, q.sql.contains("ts <"));
    }

    @Test
    public void 键集分页的游标条件是严格小于并带id破平() {
        TxnStore.Sql q = TxnStore.buildListAfter(1000L, 42L, 200);
        assertTrue("游标必须是严格小于，等号会把游标那条再给一遍：" + q.sql, q.sql.contains("ts < ?"));
        // ts 允许相等（同一毫秒到账的两笔），没有 id 破平就会漏掉同刻的下一条
        assertTrue(q.sql.contains("ts = ? AND id < ?"));
        assertTrue(q.sql.contains("ORDER BY ts DESC, id DESC"));
        assertEquals("1000", q.args[0]);
        assertEquals("1000", q.args[1]);
        assertEquals("42", q.args[2]);
        assertEquals("200", q.args[3]);
        assertEquals(4, q.args.length);
    }
}
