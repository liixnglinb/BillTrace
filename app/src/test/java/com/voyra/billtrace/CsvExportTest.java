package com.voyra.billtrace;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * CSV 转义契约。商户名可以来自短信正文的正则抽取（PayParser.merchantByPattern），
 * 属于外部可控输入，导出后被 Excel 当公式执行是真实风险面。
 */
public class CsvExportTest {

    @Test
    public void 普通值加引号() {
        assertEquals("\"美团外卖\"", TxnStore.csv("美团外卖"));
    }

    @Test
    public void 内部双引号翻倍() {
        assertEquals("\"a\"\"b\"", TxnStore.csv("a\"b"));
    }

    @Test
    public void 逗号不再被替换成中文逗号而是靠引号保护() {
        assertEquals("\"a,b\"", TxnStore.csv("a,b"));
    }

    @Test
    public void 换行与回车压成空格() {
        assertEquals("\"a b\"", TxnStore.csv("a\nb"));
        assertEquals("\"a b\"", TxnStore.csv("a\r\nb"));
    }

    @Test
    public void 公式注入前缀被中和() {
        assertEquals("\"'=1+1\"", TxnStore.csv("=1+1"));
        assertEquals("\"'+1\"", TxnStore.csv("+1"));
        assertEquals("\"'-1\"", TxnStore.csv("-1"));
        assertEquals("\"'@SUM(A1)\"", TxnStore.csv("@SUM(A1)"));
        assertEquals("\"'\t=1\"", TxnStore.csv("\t=1"));
        // 值本身就以单引号开头时仍会再加一个，得到 ''——依然是文本而不是公式
        assertEquals("\"''=cmd\"", TxnStore.csv("'=cmd"));
    }

    @Test
    public void 负数金额保持为可计算的数值不加公式前缀() {
        // 支出全是负数。若被公式注入防护加上单引号，Excel 会当文本，导出就没法求和。
        assertEquals("\"-35.80\"", TxnStore.csvNum(-35.8));
        assertEquals("\"8500.00\"", TxnStore.csvNum(8500.0));
        assertEquals("\"0.00\"", TxnStore.csvNum(0.0));
    }

    @Test
    public void 文本列仍以负号开头时会被中和() {
        assertEquals("\"'-35\"", TxnStore.csv("-35"));
    }

    @Test
    public void 空值安全() {
        assertEquals("\"\"", TxnStore.csv(null));
        assertEquals("\"\"", TxnStore.csv(""));
    }
}
