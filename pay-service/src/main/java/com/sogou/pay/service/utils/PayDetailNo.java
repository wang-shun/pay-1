package com.sogou.pay.service.utils;

import com.sogou.pay.common.utils.SequenceGenerator;
import org.springframework.stereotype.Service;


/**
 * 支付单流水号
 */
@Service
public class PayDetailNo extends SequenceGenerator {

    private static final String DATA_FORMAT = "yyyyMMddHHmmss";

    @Override
    public String getDateFormat() {
        return DATA_FORMAT;
    }
}
