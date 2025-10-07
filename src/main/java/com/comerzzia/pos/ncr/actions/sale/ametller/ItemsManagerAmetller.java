package com.comerzzia.pos.ncr.actions.sale.ametller;

import java.lang.reflect.Method;

import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.services.payments.PaymentsManager;

@Lazy(false)
@Service
@Primary
public class ItemsManagerAmetller extends ItemsManager {

    @Override
    public void resetTicket() {
        cancelPendingPayments();
        super.resetTicket();
    }

    protected void cancelPendingPayments() {
        PaymentsManager paymentsManager = ticketManager.getPaymentsManager();

        if (paymentsManager == null) {
            return;
        }

        boolean cancelInvoked = false;
        for (String methodName : new String[] { "cancelAllPayments", "cancelPayments", "cancelCurrentPayments" }) {
            try {
                Method cancelMethod = paymentsManager.getClass().getMethod(methodName);
                cancelMethod.invoke(paymentsManager);
                cancelInvoked = true;
                break;
            }
            catch (NoSuchMethodException e) {
                continue;
            }
            catch (Exception e) {
                log.error("cancelPendingPayments() - Error invoking method " + methodName + ": " + e.getMessage(), e);
                cancelInvoked = true;
                break;
            }
        }

        if (!cancelInvoked) {
            log.warn("cancelPendingPayments() - No cancel payments method available on PaymentsManager");
        }

        if (ticketManager.getTicket() != null
                && ticketManager.getTicket().getPagos() != null
                && !ticketManager.getTicket().getPagos().isEmpty()) {
            ticketManager.getTicket().getPagos().clear();
            ticketManager.getTicket().getTotales().recalcular();
        }
    }
}
