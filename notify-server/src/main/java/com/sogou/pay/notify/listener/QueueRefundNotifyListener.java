package com.sogou.pay.notify.listener;

import com.sogou.pay.common.Model.AppRefundNotifyModel;
import com.sogou.pay.common.utils.JSONUtil;
import com.sogou.pay.notify.enums.NotifyTypeEnum;
import com.sogou.pay.notify.service.NotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import java.util.HashMap;
import java.util.Map;

@Service
public class QueueRefundNotifyListener implements MessageListener {

  private static final Logger logger = LoggerFactory.getLogger(QueueRefundNotifyListener.class);

  @Autowired
  private NotifyService notifyService;

  /**
   * @param message
   * @Author qibaichao
   * @MethodName onMessage
   * @Date 2014年9月18日
   * @Description:
   */
  @Override
  public void onMessage(Message message) {
    if (message instanceof ObjectMessage) {
      final ObjectMessage objectMessage = (ObjectMessage) message;
      try {
        logger.info("[onMessage] begin, {}", JSONUtil.Bean2JSON(message));

        AppRefundNotifyModel appRefundNotifyModel = (AppRefundNotifyModel) objectMessage.getObject();

        Map notifyParam = new HashMap<String, String>();
        notifyParam.put("appId", appRefundNotifyModel.getAppId());
        notifyParam.put("orderId", appRefundNotifyModel.getOrderId());
        notifyParam.put("payId", appRefundNotifyModel.getPayId());
        notifyParam.put("refundStatus", appRefundNotifyModel.getRefundStatus());
        notifyParam.put("payAmount", appRefundNotifyModel.getPayAmount());
        notifyParam.put("refundAmount", appRefundNotifyModel.getRefundAmount());
        notifyParam.put("refundSuccessTime", appRefundNotifyModel.getRefundSuccessTime());
        notifyParam.put("sign", appRefundNotifyModel.getSign());
        notifyParam.put("signType", appRefundNotifyModel.getSignType());

        notifyService.firstNotify(NotifyTypeEnum.REFUND_NOTIFY.value(), null,
                appRefundNotifyModel.getNotifyUrl(), notifyParam);

        logger.info("[onMessage] finish, {}", JSONUtil.Bean2JSON(message));
      } catch (Exception e) {
        logger.info("[onMessage] failed, {}", e);
      }
    }
  }


}
