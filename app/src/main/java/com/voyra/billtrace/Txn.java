package com.voyra.billtrace;

/** 一条消费/收入记录。amount 有符号：负数=支出，正数=收入。 */
public class Txn {
    public long id;
    public long timeMillis;
    public double amount;
    public String merchant = "";
    public String category = "qita";
    public String sub = "";
    public String app = "";
    public String source = "";
    public String account = "";
    public String raw = "";
    public int confidence = 80;
    public int confirmed = 0;
}
