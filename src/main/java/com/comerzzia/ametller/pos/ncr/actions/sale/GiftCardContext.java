package com.comerzzia.ametller.pos.ncr.actions.sale;

import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.services.payments.methods.types.GiftCardManager;

final class GiftCardContext {

        private final String paymentCode;
        private final GiftCardManager manager;
        private final MedioPagoBean medioPago;
        private final boolean requiresConfirmation;
        private final String scoTenderType;
        private final boolean autoDetected;

        GiftCardContext(String paymentCode, GiftCardManager manager, MedioPagoBean medioPago, boolean requiresConfirmation,
                        String scoTenderType, boolean autoDetected) {
                this.paymentCode = paymentCode;
                this.manager = manager;
                this.medioPago = medioPago;
                this.requiresConfirmation = requiresConfirmation;
                this.scoTenderType = scoTenderType;
                this.autoDetected = autoDetected;
        }

        String getPaymentCode() {
                return paymentCode;
        }

        GiftCardManager getManager() {
                return manager;
        }

        MedioPagoBean getMedioPago() {
                return medioPago;
        }

        boolean requiresConfirmation() {
                return requiresConfirmation;
        }

        String getScoTenderType() {
                return scoTenderType;
        }

        boolean isAutoDetected() {
                return autoDetected;
        }
}
