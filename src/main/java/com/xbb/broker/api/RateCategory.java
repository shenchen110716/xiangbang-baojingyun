package com.xbb.broker.api;

/**
 * 分成比例的业务类目。
 *
 * <p><b>常量而不是枚举。</b>培训域现在还不存在,以后还会有别的类目;
 * 做成枚举的话每加一个类目都要改代码、发一次版,而这本该是后台配一下的事。
 * 这里只列出当前**真的有钱在流**的那些,别的由后台直接填字符串。
 */
public final class RateCategory {

    /** 岗位结算。目前唯一真的会产生佣金流水的类目。 */
    public static final String JOB = "JOB";

    /** 商品成交。商城暂不产生代发单,配得了但还不会被取到。 */
    public static final String PRODUCT = "PRODUCT";

    /** 培训。培训域尚未建立。 */
    public static final String TRAINING = "TRAINING";

    private RateCategory() { }
}
