package com.sogou.pay.manager.payment.impl;

import com.sogou.pay.common.exception.ServiceException;
import com.sogou.pay.common.result.Result;
import com.sogou.pay.common.result.ResultMap;
import com.sogou.pay.common.utils.PMap;
import com.sogou.pay.manager.model.PayCheckUpdateModle;
import com.sogou.pay.manager.model.notify.PayNotifyModel;
import com.sogou.pay.manager.notify.PayNotifyManager;
import com.sogou.pay.manager.notify.WithdrawNotifyManager;
import com.sogou.pay.manager.payment.PayCheckManager;
import com.sogou.pay.manager.notify.RefundNotifyManager;
import com.sogou.pay.service.entity.*;
import com.sogou.pay.service.enums.AgencyType;
import com.sogou.pay.service.enums.CheckStatus;
import com.sogou.pay.service.enums.OperationLogStatus;
import com.sogou.pay.service.enums.OrderType;
import com.sogou.pay.service.payment.*;
import com.sogou.pay.thirdpay.api.CheckApi;
import com.sogou.pay.thirdpay.biz.enums.CheckType;
import com.sogou.pay.thirdpay.biz.modle.OutCheckRecord;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * @Author qibaichao
 * @ClassName PayCheckManagerImpl
 * @Date 2015年2月16日
 * @Description:
 */
@Component
public class PayCheckManagerImpl implements PayCheckManager {

    private static final Logger logger = LoggerFactory.getLogger(PayCheckManagerImpl.class);

    /**
     * 对账业务编码
     */
    private List<Integer> checkBizCodeList = new ArrayList<Integer>();

    {
        checkBizCodeList.add(CheckType.PAID.getValue());
        checkBizCodeList.add(CheckType.REFUND.getValue());
        checkBizCodeList.add(CheckType.WITHDRAW.getValue());
    }

    @Autowired
    private PayCheckDayLogService payCheckDayLogService;

    @Autowired
    private PayCheckService payCheckService;

    @Autowired
    private PayCheckWaitingService payCheckWaitingService;

    @Autowired
    private PayCheckResultService payCheckResultService;

    @Autowired
    private PayCheckFeeResultService payCheckFeeResultService;

    @Autowired
    private PayAgencyMerchantService payAgencyMerchantService;

    @Autowired
    private PayCheckDiffService payCheckDiffService;

    @Autowired
    private PayCheckFeeDiffService payCheckFeeDiffService;

    @Autowired
    private PayNotifyManager payNotifyManager;

    @Autowired
    private RefundNotifyManager refundNotifyManager;

    @Autowired
    private WithdrawNotifyManager withdrawNotifyManager;

    @Autowired
    private CheckApi checkApi;

    /**
     * 每批次处理500条.
     */
    private static final int BATCH_SIZE = 500;

    /**
     * 1. 根据checkDate、agencyCode、merchantNo查询对账日志
     * 2. 不存在对账日志，创建新日志记录
     * 3. 根据各渠道分别下载支付、退款对账数据，批量入库
     * 4. 更新对账日志状态为DOWNLOADSUCCESS
     *
     * @param checkDate
     * @param agencyCode
     */
    public void downloadCheckData(String checkDate, String agencyCode) {

        logger.info(String.format("download and saving check data start! checkDate: %s|agencyCode: %s", checkDate, agencyCode));

        PayCheckDayLog payCheckDayLog = null;
        try {

            //验证商户是否存在
            List<PayAgencyMerchant> payAgencyMerchants = payAgencyMerchantService.selectPayAgencyMerchants(agencyCode);
            if (payAgencyMerchants == null || payAgencyMerchants.size() == 0) {
                logger.warn("no payAgencyMerchant for agencyCode: " + agencyCode);
                return;
            }

            payCheckDayLog = payCheckDayLogService.getByCheckDateAndAgency(checkDate, agencyCode);
            // 如果没有日志，插入一条新记录
            if (payCheckDayLog == null) {
                payCheckDayLog = new PayCheckDayLog();
                payCheckDayLog.setAgencyCode(agencyCode);
                payCheckDayLog.setStatus(OperationLogStatus.INIT.value());
                payCheckDayLog.setCheckDate(checkDate);
                payCheckDayLogService.insert(payCheckDayLog);
                payCheckDayLog = payCheckDayLogService.getByCheckDateAndAgency(checkDate, agencyCode);
            }

            for (PayAgencyMerchant payAgencyMerchant : payAgencyMerchants) {

                //删除已下载数据
                payCheckService.deleteInfo(checkDate, agencyCode, payAgencyMerchant.getMerchantNo());
                //支付宝
                if (agencyCode == AgencyType.ALIPAY.name()) {
                    //获取支付记录
                    innerAddAlipayCheckData(checkDate, payAgencyMerchant, CheckType.PAID);
                    //获取退款记录
                    innerAddAlipayCheckData(checkDate, payAgencyMerchant, CheckType.REFUND);
                    //获取支付宝手续费
                    innerAddAlipayCheckData(checkDate, payAgencyMerchant, CheckType.CHARGED);
                    //获取提现记录
                    innerAddAlipayCheckData(checkDate, payAgencyMerchant, CheckType.WITHDRAW);
                }
                //财付通
                else if (agencyCode == AgencyType.TENPAY.name()) {

                    innerAddTenpayCheckData(checkDate, payAgencyMerchant, CheckType.ALL);
                }
                //微信
                else if (agencyCode == AgencyType.WECHAT.name()) {

                    innerAddWechatCheckData(checkDate, payAgencyMerchant, CheckType.PAID);
                    innerAddWechatCheckData(checkDate, payAgencyMerchant, CheckType.REFUND);
                }

                //快钱
                else if (agencyCode == AgencyType.BILL99.name()) {

                    innerAddBill99CheckData(checkDate, payAgencyMerchant, CheckType.PAID);
                    innerAddBill99CheckData(checkDate, payAgencyMerchant, CheckType.REFUND);
                }
            }

            logger.info(String.format("download and saving check data succeed! checkDate: %s|agencyCode: %s", checkDate, agencyCode));

            payCheckDayLogService.updateStatus(payCheckDayLog.getId(), OperationLogStatus.DOWNLOADSUCCESS.value(),
                    payCheckDayLog.getVersion(), OperationLogStatus.DOWNLOADSUCCESS.name());

        } catch (Exception e) {
            logger.error(e.getMessage());
            try {
                payCheckDayLogService.updateStatus(payCheckDayLog.getId(), OperationLogStatus.FAIL.value(), payCheckDayLog.getVersion(), OperationLogStatus.FAIL.name());
            } catch (ServiceException se) {
                logger.error(se.getMessage());
            }
        }
    }

    /**
     * 1.对账并更新相关的对账状态
     * 2.更新对账日志状态为SUCCESS
     *
     * @param checkDate
     * @param agencyCode
     */
    public void checkData(String checkDate, String agencyCode) {

        logger.info(String.format("check and updating data start!checkDate: %s| agencyCode: %s", checkDate, agencyCode));

        PayCheckDayLog payCheckDayLog = null;
        try {
            payCheckDayLog = payCheckDayLogService.getByCheckDateAndAgency(checkDate, agencyCode);

            // 对账文件下载验证
            if (payCheckDayLog == null || payCheckDayLog.getStatus() != OperationLogStatus.DOWNLOADSUCCESS.value()) {
                logger.warn(String
                        .format("payCheckDayLog record is not exist or record download error. checkDate: %s| agencyCode: %s", checkDate, agencyCode));
                return;
            }
            /**
             * 根据业务编码勾兑
             * 1. 支付
             * 2. 退款
             */
            int total = 0;
            for (int bizCode : checkBizCodeList) {

                int page = 0;
                boolean hasNext = true;

                while (hasNext) {
                    // 查询指定渠道、日期范围内，未对账成功的记录，每次查500条
                    List<Map<String, Object>> list = payCheckService.queryByMerAndDateAndBizCode(checkDate, agencyCode, bizCode,
                            page * BATCH_SIZE, BATCH_SIZE);
                    int size = list.size();
                    if (size > 0) {
                        doBatchUpdate(list);
                        total += size;
                    }
                    // 查询结果数量小于每批次的数量，说明已经是最后一页了
                    if (size < BATCH_SIZE) {
                        hasNext = false;
                    }
                    page++;
                }
            }

            // 刷新统计数据
            if (total > 0) {
                //自动勾稽处理
                updatePayCheckDiff();
                // 刷新对账结果数据
                updatePayCheckResult(checkDate, agencyCode);
                //财付通不对手续费
                if (!AgencyType.TENPAY.name().equals(agencyCode)) {
                    //刷新手续费对账结果数据
                    updatePayCheckFeeResult(checkDate, agencyCode);
                }
            }

            //修改对账日志为对账成功
            payCheckDayLogService.updateStatus(payCheckDayLog.getId(), OperationLogStatus.SUCCESS.value(),
                    payCheckDayLog.getVersion(), OperationLogStatus.SUCCESS.name());

            logger.info(String.format("check and updating  check data finished!checkDate: %s| agencyCode: %s|total: %d",
                    checkDate, agencyCode, total));
        } catch (Exception ex) {
            ex.printStackTrace();
            logger.error(ex.getMessage());
            try {
                payCheckDayLogService.updateStatus(payCheckDayLog.getId(), OperationLogStatus.FAIL.value(), payCheckDayLog.getVersion(), OperationLogStatus.FAIL.name());
            } catch (ServiceException se) {
                logger.error(se.getMessage());
            }
        }
    }

    /**
     * 对账业务处理
     *
     * @param list
     */
    @Transactional(value = "transactionManager")
    public void doBatchUpdate(List<Map<String, Object>> list) throws Exception {

        List<PayCheckUpdateModle> payCheckUpdates = new ArrayList<PayCheckUpdateModle>();

        List<PayCheckUpdateModle> payCheckWaitingUpdates = new ArrayList<PayCheckUpdateModle>();

        for (Map<String, Object> item : list) {

            PayCheckUpdateModle payCheckUppdateModle = new PayCheckUpdateModle();

            String instructId = (String) item.get("instruct_id");
            payCheckUppdateModle.setInstructId(instructId);

            // 对账金额
            BigDecimal pcAmt = BigDecimal.valueOf(Double.parseDouble(item.get("pc_amt").toString()));

            // 待对账算金额
            BigDecimal pcwAmt = item.get("pcw_amt") == null ? null : BigDecimal.valueOf(Double.parseDouble(item.get("pcw_amt").toString()));

            // 对账ID
            long payCheckId = Long.valueOf(item.get("pay_check_id").toString());
            payCheckUppdateModle.setPayCheckId(payCheckId);

            int bizCode = Integer.valueOf(item.get("biz_code").toString());

            /**
             * 在t_pay_check_waiting表中缺失记录，检查是否是丢单
             */
            if (pcwAmt == null) {

                PayCheck payCheck = payCheckService.getByInstructIdAndBizCode(instructId, bizCode);
                try {
                    //补单
                    Result result = repairNotify(payCheck);
                    if (Result.isSuccess(result)) {
                        PayCheckWaiting payCheckWaiting = payCheckWaitingService.getByInstructId(instructId);
                        if (payCheckWaiting != null) {
                            pcwAmt = payCheckWaiting.getBizAmt();
                        }
                    } else {
                        logger.warn("repair process error! instructId=" + instructId);
                    }
                } catch (Exception e) {
                    logger.warn("repair process  error! instructId=" + instructId, e);
                }
            }
            /**
             * 对账与待对账记录匹配，比较金额
             */
            if (pcwAmt != null) {

                // 金额匹配
                if (pcAmt.compareTo(pcwAmt) == 0) {
                    payCheckUppdateModle.setPayCheckStatus(CheckStatus.SUCCESS.value());
                    payCheckUppdateModle.setPayCheckWaitingStatus(CheckStatus.SUCCESS.value());
                }
                // 金额不匹配
                else {
                    payCheckUppdateModle.setPayCheckStatus(CheckStatus.UNBALANCE.value());
                    payCheckUppdateModle.setPayCheckWaitingStatus(CheckStatus.UNBALANCE.value());
                }
                payCheckUpdates.add(payCheckUppdateModle);
                payCheckWaitingUpdates.add(payCheckUppdateModle);
            }
            // 对方多账
            else {
                payCheckUppdateModle.setPayCheckStatus(CheckStatus.LOST.value());
                payCheckUpdates.add(payCheckUppdateModle);
            }
        }

        if (payCheckUpdates.size() != 0) {
            payCheckService.batchUpdateStatus(payCheckUpdates);
        }
        if (payCheckWaitingUpdates.size() != 0) {
            payCheckWaitingService.batchUpdateStatus(payCheckWaitingUpdates);
        }
    }

    /**
     * 补单
     *
     * @param payCheck
     */
    private Result repairNotify(PayCheck payCheck) {

        Result result = ResultMap.build();
        if (payCheck.getBizCode() == OrderType.PAYCASH.getValue()) {
            //支付补单
            PayNotifyModel payNotifyModel = new PayNotifyModel();
            payNotifyModel.setPayDetailId(payCheck.getInstructId());
            payNotifyModel.setAgencyOrderId(payCheck.getOutOrderId());
            payNotifyModel.setBankOrderId(null);
            //payNotifyModel.setPayStatus(1);
            payNotifyModel.setAgencyPayTime(payCheck.getOutTransTime());
            payNotifyModel.setTrueMoney(payCheck.getBizAmt());
            result = payNotifyManager.doProcess(payNotifyModel);
        } else if (payCheck.getBizCode() == OrderType.REFUND.getValue()) {
            //退款补单
            result = refundNotifyManager.repairRefundOrder(payCheck.getInstructId(), payCheck.getOutOrderId());
        } else if (payCheck.getBizCode() == OrderType.WITHDRAW.getValue()) {
            //提现补单
            PMap params = new PMap<String, Object>();
            params.put("instructId", payCheck.getInstructId());
            params.put("outOrderId", payCheck.getOutOrderId());
            params.put("outTransTime", payCheck.getOutTransTime());
            params.put("bizAmt", payCheck.getBizAmt());
            params.put("accessPlatform", 1);
            params.put("appId", 0);
            params.put("agencyCode", payCheck.getAgencyCode());
            params.put("merchantNo", payCheck.getMerchantNo());
            params.put("payType", 99);
            params.put("bankCode", payCheck.getAgencyCode());
            result = withdrawNotifyManager.doProcess(params);
        }
        return result;
    }

    /**
     * 1.查询未处理的差异信息
     * 2.自动处理
     *
     * @throws Exception
     */
    @Transactional(value = "transactionManager")
    public void updatePayCheckDiff() throws Exception {

        int count = payCheckDiffService.selectUnResolvedCount();
        if (count > 0) {
            List<PayCheckDiff> diffList = payCheckDiffService.selectUnResolvedList();
            for (PayCheckDiff payCheckDiff : diffList) {
                PayCheck payCheck = payCheckService.getByInstructIdAndBizCode(payCheckDiff.getInstructId(), payCheckDiff.getBizCode());
                if (payCheck != null && payCheck.getStatus() == CheckStatus.SUCCESS.value()) {
                    payCheckDiffService.updateStatus(payCheckDiff.getId(), 1, "auto handle success");
                }
            }
        }
    }

    /**
     * 生成对账结果数据
     *
     * @param checkDate
     */
    @Transactional(value = "transactionManager")
    public void updatePayCheckResult(String checkDate, String agencyCode) throws Exception {
        // 先删除再插入
        payCheckResultService.delete(checkDate, agencyCode);
        payCheckResultService.insert(checkDate, agencyCode);

        List<PayCheckResult> payCheckResultList = payCheckResultService.queryByDateAndAgency(checkDate, agencyCode);

        for (PayCheckResult payCheckResult : payCheckResultList) {

            BigDecimal outTotalAmt = payCheckResult.getOutTotalAmt();
            int outTotalNum = payCheckResult.getOutTotalNum();
            BigDecimal totalAmt = payCheckResult.getTotalAmt();
            int totalNum = payCheckResult.getTotalNum();

            int status = 0;
            if (outTotalNum == totalNum && outTotalAmt.compareTo(totalAmt) == 0) {
                //对账成功
                status = 1;
            } else {
                //对账失败
                status = 2;
            }
            payCheckResultService.updateStatus(payCheckResult.getId(), status);
        }
        //生成对账差异信息
        payCheckDiffService.delete(checkDate, agencyCode);
        payCheckDiffService.insertAmtDiff(checkDate, agencyCode);
        payCheckDiffService.insertOutMoreDiff(checkDate, agencyCode);
        payCheckDiffService.insertOutLessDiff(checkDate, agencyCode);
    }

    /**
     * 修改手续费对账结果
     *
     * @param checkDate
     * @param agencyCode
     * @throws Exception
     */
    @Transactional(value = "transactionManager")
    public void updatePayCheckFeeResult(String checkDate, String agencyCode) throws Exception {

        // 先删除再插入
        payCheckFeeResultService.delete(checkDate, agencyCode);
        payCheckFeeResultService.insert(checkDate, agencyCode);

        List<PayCheckFeeResult> payCheckFeeResultList = payCheckFeeResultService.queryByDateAndAgency(checkDate, agencyCode);

        for (PayCheckFeeResult payCheckFeeResult : payCheckFeeResultList) {

            BigDecimal outTotalFee = payCheckFeeResult.getOutTotalFee().setScale(2, BigDecimal.ROUND_HALF_UP);
            int outTotalNum = payCheckFeeResult.getOutTotalNum();
            BigDecimal totalFee = payCheckFeeResult.getTotalFee().setScale(2, BigDecimal.ROUND_HALF_UP);
            int totalNum = payCheckFeeResult.getTotalNum();

            int status = 0;
            if (outTotalNum == totalNum && outTotalFee.compareTo(totalFee) == 0) {
                //对账成功
                status = 1;
            } else {
                //对账失败
                status = 2;
            }
            payCheckFeeResultService.updateFeeStatus(payCheckFeeResult.getId(), status);
        }

        //生成手续费差异信息
        payCheckFeeDiffService.delete(checkDate, agencyCode);
        payCheckFeeDiffService.insertFeeDiff(checkDate, agencyCode);
    }


    /**
     * 支付宝对账数据插入
     *
     * @param checkDate
     * @param payAgencyMerchant
     * @param checkType
     */
    private void innerAddAlipayCheckData(String checkDate, PayAgencyMerchant payAgencyMerchant, CheckType checkType)
            throws Exception {


        String key = payAgencyMerchant.getEncryptKey();
        String merchantNo = payAgencyMerchant.getMerchantNo();
        String agencyCode = payAgencyMerchant.getAgencyCode();

        // yyyyMMdd -> yyyy-MM-dd
        String alipayCheckDate = new StringBuilder(checkDate).insert(6, '-').insert(4, '-').toString();

        String startTime = alipayCheckDate + " 00:00:00";
        String endTime = alipayCheckDate + " 23:59:59";
        int pageNo = 1;
        boolean hasNext = true;

        while (hasNext) {

            PMap params = new PMap();
            params.put("checkType", checkType);
            params.put("merchantNo", merchantNo);
            params.put("startTime", startTime);
            params.put("endTime", endTime);
            params.put("key", key);
            params.put("pageNo", pageNo);
            params.put("pageSize", BATCH_SIZE);

            ResultMap result = checkApi.doQueryAlipay(params);

            if (!Result.isSuccess(result)) {
                logger.error(String.format("result status is not success for checkDate:%s, agencyCode:%s, merchantNo:%s, checkType:%s",
                        checkDate, agencyCode, merchantNo, checkType));
                throw new Exception(result.getStatus().getMessage());
            }
            List<OutCheckRecord> records = (List<OutCheckRecord>) result.getData().get("records");
            if (CollectionUtils.isNotEmpty(records)) {
                //修改
                if (checkType.getValue() == CheckType.CHARGED.getValue()) {
                    payCheckService.batchUpdateFee(records);
                } else {
                    //批量插入
                    doBatchInsert(checkDate, agencyCode, merchantNo, checkType.getValue(), records);
                }
            }
            //是否有下一页
            hasNext = (boolean) result.getData().get("hasNextPage");
            // 翻页
            pageNo++;
        }
    }

    /**
     * 财付通对账数据插入
     *
     * @param checkDate
     * @param payAgencyMerchant
     * @param checkType
     */
    private void innerAddTenpayCheckData(String checkDate, PayAgencyMerchant payAgencyMerchant, CheckType checkType)
            throws Exception {


        String key = payAgencyMerchant.getEncryptKey();
        String merchantNo = payAgencyMerchant.getMerchantNo();
        String agencyCode = payAgencyMerchant.getAgencyCode();
        /**
         * yyyyMMdd -> yyyy-MM-dd
         */
        String tenpayCheckDate = new StringBuilder(checkDate).insert(6, '-').insert(4, '-').toString();

        PMap params = new PMap();

        params.put("checkDate", tenpayCheckDate);
        params.put("checkType", checkType);
        params.put("merchantNo", merchantNo);
        params.put("key", key);

        ResultMap result = checkApi.doQueryTenpay(params);

        if (!Result.isSuccess(result)) {
            logger.error(String.format("result status is not success for checkDate:%s, agencyCode:%s, merchantNo:%s, checkType:%s",
                    checkDate, agencyCode, merchantNo, checkType));
            throw new RuntimeException(result.getStatus().getMessage());
        }
        //支付数据入库
        List<OutCheckRecord> payRecords = (List<OutCheckRecord>) result.getData().get("payRecords");
        if (CollectionUtils.isNotEmpty(payRecords)) {
            //批量插入
            doBatchInsert(checkDate, agencyCode, merchantNo, CheckType.PAID.getValue(), payRecords);
        }
        //退款数据入库
        List<OutCheckRecord> refRecords = (List<OutCheckRecord>) result.getData().get("refRecords");
        if (CollectionUtils.isNotEmpty(refRecords)) {
            //批量插入
            doBatchInsert(checkDate, agencyCode, merchantNo, CheckType.REFUND.getValue(), refRecords);
        }

    }


    /**
     * 微信对账数据插入
     *
     * @param checkDate
     * @param payAgencyMerchant
     * @param checkType
     */
    private void innerAddWechatCheckData(String checkDate, PayAgencyMerchant payAgencyMerchant, CheckType checkType)
            throws Exception {

        String key = payAgencyMerchant.getEncryptKey();
        String merchantNo = payAgencyMerchant.getMerchantNo();
        String agencyCode = payAgencyMerchant.getAgencyCode();
        //获取公众服务号
        String wx_service_no = payAgencyMerchant.getSellerEmail();

        PMap params = new PMap();
        params.put("appId", wx_service_no);
        params.put("checkDate", checkDate);
        params.put("checkType", checkType);
        params.put("merchantNo", merchantNo);
        params.put("key", key);

        ResultMap result = checkApi.doQueryWechat(params);

        if (!Result.isSuccess(result)) {
            logger.error(String.format("result status is not success for checkDate:%s, agencyCode:%s, merchantNo:%s, checkType:%s",
                    checkDate, agencyCode, merchantNo, checkType));
            throw new RuntimeException(result.getStatus().getMessage());
        }

        List<OutCheckRecord> records = (List<OutCheckRecord>) result.getData().get("records");
        if (CollectionUtils.isNotEmpty(records)) {
            //批量插入
            doBatchInsert(checkDate, agencyCode, merchantNo, checkType.getValue(), records);
        }
    }

    /**
     * 快钱对账数据插入
     *
     * @param checkDate
     * @param payAgencyMerchant
     * @param checkType
     */
    private void innerAddBill99CheckData(String checkDate, PayAgencyMerchant payAgencyMerchant, CheckType checkType)
            throws Exception {

        String key = payAgencyMerchant.getEncryptKey();
        String merchantNo = payAgencyMerchant.getMerchantNo();
        String agencyCode = payAgencyMerchant.getAgencyCode();
        String startTime = "";
        String endTime = "";

        if (checkType == CheckType.PAID) {
            startTime = checkDate + "000000";
            endTime = checkDate + "235959";
        } else if (checkType == CheckType.REFUND) {
            startTime = checkDate;
            endTime = checkDate;
        }

        PMap params = new PMap();
        params.put("checkType", checkType);
        params.put("merchantNo", merchantNo);
        params.put("startTime", startTime);
        params.put("endTime", endTime);
        params.put("key", key);
        int pageNo = 1;
        ResultMap result = null;

        while (true) {

            params.put("pageNo", pageNo);
            if (checkType == CheckType.PAID) {
                result = checkApi.doPayQueryBill99(params);
            } else if (checkType == CheckType.REFUND) {
                result = checkApi.doRefundQueryBill99(params);
            }

            if (!Result.isSuccess(result)) {
                logger.error(String.format("result status is not success for checkDate:%s, agencyCode:%s, merchantNo:%s, checkType:%s",
                        checkDate, agencyCode, merchantNo, checkType));
                throw new RuntimeException(result.getStatus().getMessage());
            }

            List<OutCheckRecord> records = (List<OutCheckRecord>) result.getData().get("records");
            if (CollectionUtils.isEmpty(records)) {
                break;
            }
            //批量插入
            doBatchInsert(checkDate, agencyCode, merchantNo, checkType.getValue(), records);
            // 翻页
            pageNo++;
        }

    }


    //    /**
//     * @param orderType
//     * @return
//     */
//    private CheckType getCheckType(CheckType CheckType) {
//        switch (orderType) {
//            case PAID:
//                return CheckType.PAID;
//            case REFUND:
//                return CheckType.REFUND;
//            case CHARGED:
//                return CheckType.PAID;
//            default:
//                throw new IllegalArgumentException("can't handle bizCode: " + orderType.getValue());
//        }
//    }


    /**
     * 批量插入
     *
     * @param checkDate
     * @param agencyCode
     * @param merchantNo
     * @param bizCode
     * @param outClearRecords
     * @throws Exception
     */
    private void doBatchInsert(String checkDate, String agencyCode, String merchantNo, int bizCode, List<OutCheckRecord> outClearRecords)
            throws Exception {
        int i = 0;
        List<PayCheck> payCheckList = new ArrayList<PayCheck>();
        // 解析成功，批量插入数据库
        PayCheck payCheck = null;
        for (OutCheckRecord outClearRecord : outClearRecords) {
            payCheck = new PayCheck();
            payCheck.setInstructId(outClearRecord.getPayNo());
            payCheck.setOutOrderId((outClearRecord.getOutPayNo()));
            payCheck.setBizCode(bizCode);
            payCheck.setOutTransTime(outClearRecord.getOutTransTime());
            payCheck.setBizAmt(outClearRecord.getMoney());
            payCheck.setCommissionFeeAmt(outClearRecord.getCommssionFee());
            payCheck.setCheckDate(checkDate);
            payCheck.setAgencyCode(agencyCode);
            payCheck.setMerchantNo(merchantNo);
            payCheck.setBalance(outClearRecord.getBalance());
            payCheckList.add(payCheck);
            i++;
            if (i % BATCH_SIZE == 0) {
                payCheckService.batchInsert(payCheckList);
                payCheckList.clear();
            }
        }
        if (payCheckList.size() != 0) {
            payCheckService.batchInsert(payCheckList);
        }
    }

    private String buildProcessedKey(String merchantNo, String clearType, String checkDate) {

        return merchantNo + ";" + clearType + ";" + checkDate;
    }

}
