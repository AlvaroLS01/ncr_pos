package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.core.servicios.variables.Variables;
import com.comerzzia.pos.ncr.actions.sale.PayManager;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.DataNeededReply;
import com.comerzzia.pos.ncr.messages.Tender;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.persistence.giftcard.GiftCardBean;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.GiftCardManager;
import com.comerzzia.pos.util.i18n.I18N;

@Lazy(false)
@Service
@Primary
public class AmetllerPayManager extends PayManager {

    private static final Logger log = Logger.getLogger(AmetllerPayManager.class);
    private static final String AUTO_TENDER_TYPE = "OTRASTARJETAS";
    private static final String AUTO_TENDER_TYPE_ALT = "OTHERCARDS";
    private static final String EXPLICIT_TENDER = "GIFTCARD";
    private static final String DIALOG_CONFIRM_TYPE = "4";
    private static final String DIALOG_CONFIRM_ID = "1";

    @Autowired
    private Sesion sesion;

    @Autowired
    private VariablesServices variablesServices;

    private PendingPayment pendingPayment;

    @PostConstruct
    @Override
    public void init() {
        super.init();
        ncrController.registerActionManager(DataNeededReply.class, this);
    }

    @Override
    public void processMessage(BasicNCRMessage message) {
        if (message instanceof DataNeededReply) {
            if (!handleDataNeededReply((DataNeededReply) message)) {
                log.warn("processMessage() - DataNeededReply not managed by gift card flow");
            }
            return;
        }

        super.processMessage(message);
    }

    @Override
    protected void activateTenderMode() {
        if (ticketManager instanceof AmetllerScoTicketManager) {
            ((AmetllerScoTicketManager) ticketManager).setDescuento25Activo(false);
        }
        super.activateTenderMode();
    }

    @Override
    protected void trayPay(Tender message) {
        String tenderTypeRaw = StringUtils.trimToEmpty(message.getFieldValue(Tender.TenderType));
        String normalizedTender = tenderTypeRaw.replace(" ", "").toUpperCase(Locale.ROOT);
        String cardNumber = StringUtils.trimToNull(message.getFieldValue(Tender.UPC));

        PaymentsManager paymentsManager = ticketManager.getPaymentsManager();
        GiftCardContext context = resolveGiftCardContext(tenderTypeRaw, normalizedTender, cardNumber, paymentsManager);

        if (context == null) {
            super.trayPay(message);
            return;
        }

        BigDecimal amount = parseTenderAmount(message);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            amount = ticketManager.getTicket().getTotales().getPendiente();
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            sendGiftCardError(I18N.getTexto("El importe indicado no es válido."), context.scoTenderType);
            return;
        }

        PendingPayment payment = new PendingPayment(message, context, cardNumber, amount);

        if (context.requiresConfirmation) {
            pendingPayment = payment;
            sendConfirmationDialog(context);
            return;
        }

        executeGiftCardPayment(payment);
    }

    private GiftCardContext resolveGiftCardContext(String tenderTypeRaw, String normalizedTender, String cardNumber,
            PaymentsManager paymentsManager) {
        if (paymentsManager == null) {
            return null;
        }

        Map<String, PaymentMethodManager> managers = paymentsManager.getPaymentsMehtodManagerAvailables();
        if (managers == null || managers.isEmpty()) {
            return null;
        }

        // explicit tender mapping
        String mappedCode = null;
        try {
            mappedCode = scoTenderTypeToComerzziaPaymentCode(tenderTypeRaw);
        } catch (RuntimeException ex) {
            mappedCode = null;
        }

        if (mappedCode != null) {
            PaymentMethodManager manager = managers.get(mappedCode);
            if (manager instanceof GiftCardManager) {
                MedioPagoBean medioPago = mediosPagosService.getMedioPago(mappedCode);
                return new GiftCardContext(mappedCode, (GiftCardManager) manager, medioPago, false, tenderTypeRaw,
                        false);
            }
        }

        boolean autoDetected = AUTO_TENDER_TYPE.equals(normalizedTender) || AUTO_TENDER_TYPE_ALT.equals(normalizedTender)
                || EXPLICIT_TENDER.equals(normalizedTender);

        if (!autoDetected) {
            return null;
        }

        if (StringUtils.isBlank(cardNumber)) {
            sendGiftCardError(I18N.getTexto("No se ha informado ningún número de tarjeta regalo."), tenderTypeRaw);
            return null;
        }

        GiftCardManager foundManager = null;
        String foundCode = null;

        for (Map.Entry<String, PaymentMethodManager> entry : managers.entrySet()) {
            if (entry.getValue() instanceof GiftCardManager) {
                if (foundManager != null) {
                    // more than one candidate, rely on explicit tender mapping
                    foundManager = null;
                    break;
                }
                foundManager = (GiftCardManager) entry.getValue();
                foundCode = entry.getKey();
            }
        }

        if (foundManager == null) {
            sendGiftCardError(I18N.getTexto("Medio de pago de tarjeta regalo no disponible."), tenderTypeRaw);
            return null;
        }

        MedioPagoBean medioPago = mediosPagosService.getMedioPago(foundCode);
        return new GiftCardContext(foundCode, foundManager, medioPago, true, tenderTypeRaw, true);
    }

    private void sendConfirmationDialog(GiftCardContext context) {
        DataNeeded dialog = new DataNeeded();
        dialog.setFieldValue(DataNeeded.Type, DIALOG_CONFIRM_TYPE);
        dialog.setFieldValue(DataNeeded.Id, DIALOG_CONFIRM_ID);
        dialog.setFieldValue(DataNeeded.Mode, "0");

        String description = context.medioPago != null ? context.medioPago.getDesMedioPago()
                : I18N.getTexto("Tarjeta regalo");

        dialog.setFieldValue(DataNeeded.TopCaption1,
                MessageFormat.format(I18N.getTexto("¿Desea usar su tarjeta {0}?"), description));
        dialog.setFieldValue(DataNeeded.SummaryInstruction1, I18N.getTexto("Pulse una opción"));
        dialog.setFieldValue(DataNeeded.ButtonData1, "TxSi");
        dialog.setFieldValue(DataNeeded.ButtonText1, I18N.getTexto("SI"));
        dialog.setFieldValue(DataNeeded.ButtonData2, "TxNo");
        dialog.setFieldValue(DataNeeded.ButtonText2, I18N.getTexto("NO"));
        dialog.setFieldValue(DataNeeded.EnableScanner, "0");
        dialog.setFieldValue(DataNeeded.HideGoBack, "1");

        ncrController.sendMessage(dialog);
    }

    private boolean handleDataNeededReply(DataNeededReply reply) {
        String type = reply.getFieldValue(DataNeededReply.Type);
        String id = reply.getFieldValue(DataNeededReply.Id);

        if (!DIALOG_CONFIRM_TYPE.equals(type) || !DIALOG_CONFIRM_ID.equals(id)) {
            return false;
        }

        PendingPayment payment = pendingPayment;
        pendingPayment = null;

        sendCloseDataNeeded();

        if (payment == null) {
            log.warn("handleDataNeededReply() - Confirmation received without pending payment");
            return true;
        }

        String option = reply.getFieldValue(DataNeededReply.Data1);
        if (StringUtils.equalsIgnoreCase("TxSi", option)) {
            executeGiftCardPayment(payment);
        } else {
            sendGiftCardError(I18N.getTexto("Operación cancelada por el usuario"), payment.context.scoTenderType);
            itemsManager.sendTotals();
        }

        return true;
    }

    private void sendCloseDataNeeded() {
        DataNeeded close = new DataNeeded();
        close.setFieldValue(DataNeeded.Type, "0");
        close.setFieldValue(DataNeeded.Id, "0");
        close.setFieldValue(DataNeeded.Mode, "0");
        ncrController.sendMessage(close);
    }

    private BigDecimal parseTenderAmount(Tender message) {
        String amountValue = message.getFieldValue(Tender.Amount);
        if (StringUtils.isBlank(amountValue)) {
            return null;
        }
        try {
            return new BigDecimal(amountValue).divide(BigDecimal.valueOf(100));
        } catch (NumberFormatException e) {
            log.error("parseTenderAmount() - Unable to parse tender amount: " + amountValue, e);
            return null;
        }
    }

    private void executeGiftCardPayment(PendingPayment payment) {
        try {
            ncrController.sendWaitState(I18N.getTexto("Validando tarjeta..."));

            GiftCardBean giftCard = consultGiftCard(payment.cardNumber);
            BigDecimal available = calculateAvailableBalance(giftCard);

            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                throw new GiftCardException(I18N.getTexto("El saldo de la tarjeta regalo no es suficiente."));
            }

            BigDecimal amountToCharge = payment.amount.min(available);
            if (amountToCharge.compareTo(BigDecimal.ZERO) <= 0) {
                throw new GiftCardException(I18N.getTexto("El saldo de la tarjeta regalo no es suficiente."));
            }

            giftCard.setNumTarjetaRegalo(payment.cardNumber);
            giftCard.setImportePago(amountToCharge);
            if (giftCard.getSaldo() == null || giftCard.getSaldo().compareTo(BigDecimal.ZERO) <= 0) {
                giftCard.setSaldo(amountToCharge);
            }
            if (giftCard.getSaldoProvisional() == null) {
                giftCard.setSaldoProvisional(BigDecimal.ZERO);
            }

            payment.context.manager.addParameter(GiftCardManager.PARAM_TARJETA, giftCard);

            payment.message.setFieldIntValue(Tender.Amount, amountToCharge.setScale(2, RoundingMode.HALF_UP));

            PaymentsManager paymentsManager = ticketManager.getPaymentsManager();
            paymentsManager.pay(payment.context.paymentCode, amountToCharge);
        } catch (GiftCardException e) {
            log.error("executeGiftCardPayment() - " + e.getMessage(), e);
            sendGiftCardError(e.getMessage(), payment.context.scoTenderType);
        } catch (Exception e) {
            log.error("executeGiftCardPayment() - Unexpected error: " + e.getMessage(), e);
            sendGiftCardError(I18N.getTexto("No se ha podido validar la tarjeta regalo."),
                    payment.context.scoTenderType);
        } finally {
            ncrController.sendFinishWaitState();
        }
    }

    private GiftCardBean consultGiftCard(String cardNumber) throws GiftCardException {
        if (StringUtils.isBlank(cardNumber)) {
            throw new GiftCardException(I18N.getTexto("No se ha informado ningún número de tarjeta regalo."));
        }

        try {
            Class<?> requestClass = Class.forName(
                    "com.comerzzia.api.rest.client.fidelizados.ConsultarFidelizadoRequestRest");
            Object request = requestClass.getDeclaredConstructor().newInstance();

            invokeSetter(request, "setNumeroTarjeta", cardNumber);
            invokeSetter(request, "setUidActividad", sesion.getAplicacion().getUidActividad());

            String apiKey = variablesServices.getVariableAsString(Variables.WEBSERVICES_APIKEY);
            if (StringUtils.isNotBlank(apiKey)) {
                invokeSetter(request, "setApiKey", apiKey);
            }

            Class<?> restClass = Class.forName("com.comerzzia.api.rest.client.fidelizados.FidelizadosRest");
            Method method = restClass.getMethod("getTarjetaRegalo", requestClass);
            Object response = method.invoke(null, request);

            Object payload = extractGiftCardPayload(response);
            GiftCardBean bean = convertToGiftCardBean(payload, cardNumber);
            if (bean == null) {
                throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."));
            }
            return bean;
        } catch (GiftCardException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            throw new GiftCardException(I18N.getTexto("Servicio de tarjetas regalo no disponible."), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
            throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), cause);
        } catch (Exception e) {
            throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
        }
    }

    private Object extractGiftCardPayload(Object response) throws GiftCardException {
        if (response == null) {
            return null;
        }

        if (response instanceof GiftCardBean) {
            return response;
        }

        Method getter = findNoArgMethod(response.getClass(), "getTarjetaRegalo", "getTarjeta", "getGiftCard");
        if (getter != null) {
            try {
                return getter.invoke(response);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
            }
        }

        return response;
    }

    private GiftCardBean convertToGiftCardBean(Object payload, String cardNumber) {
        if (payload == null) {
            return null;
        }

        if (payload instanceof GiftCardBean) {
            GiftCardBean bean = (GiftCardBean) payload;
            if (StringUtils.isBlank(bean.getNumTarjetaRegalo())) {
                bean.setNumTarjetaRegalo(cardNumber);
            }
            ensureGiftCardDefaults(bean);
            return bean;
        }

        GiftCardBean bean = new GiftCardBean();
        bean.setNumTarjetaRegalo(cardNumber);

        BigDecimal saldo = readBigDecimal(payload, "getSaldo");
        BigDecimal saldoProvisional = readBigDecimal(payload, "getSaldoProvisional");
        BigDecimal saldoTotal = readBigDecimal(payload, "getSaldoTotal");
        BigDecimal saldoDisponible = readBigDecimal(payload, "getSaldoDisponible");

        if (saldo == null) {
            saldo = saldoTotal != null ? saldoTotal : saldoDisponible;
        }
        if (saldo == null) {
            saldo = BigDecimal.ZERO;
        }
        if (saldoProvisional == null) {
            saldoProvisional = BigDecimal.ZERO;
        }

        bean.setSaldo(saldo);
        bean.setSaldoProvisional(saldoProvisional);

        return bean;
    }

    private BigDecimal readBigDecimal(Object source, String methodName) {
        if (source == null) {
            return null;
        }

        Method method = findNoArgMethod(source.getClass(), methodName);
        if (method == null) {
            return null;
        }

        try {
            Object value = method.invoke(source);
            if (value == null) {
                return null;
            }
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            if (value instanceof Number) {
                return BigDecimal.valueOf(((Number) value).doubleValue());
            }
            return new BigDecimal(value.toString());
        } catch (IllegalAccessException | InvocationTargetException | NumberFormatException e) {
            return null;
        }
    }

    private Method findNoArgMethod(Class<?> type, String... names) {
        for (String name : names) {
            if (name == null) {
                continue;
            }
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                // continue searching
            }
        }
        return null;
    }

    private BigDecimal calculateAvailableBalance(GiftCardBean bean) {
        BigDecimal saldo = bean.getSaldo() != null ? bean.getSaldo() : BigDecimal.ZERO;
        BigDecimal provisional = bean.getSaldoProvisional() != null ? bean.getSaldoProvisional() : BigDecimal.ZERO;
        BigDecimal total = saldo.add(provisional);
        return total.compareTo(BigDecimal.ZERO) > 0 ? total : saldo;
    }

    private void ensureGiftCardDefaults(GiftCardBean bean) {
        if (bean.getSaldo() == null) {
            bean.setSaldo(BigDecimal.ZERO);
        }
        if (bean.getSaldoProvisional() == null) {
            bean.setSaldoProvisional(BigDecimal.ZERO);
        }
    }

    private void invokeSetter(Object target, String methodName, Object value)
            throws IllegalAccessException, InvocationTargetException {
        if (target == null || value == null) {
            return;
        }
        Method method = findMethod(target.getClass(), methodName, value.getClass());
        if (method == null && value instanceof String) {
            method = findMethod(target.getClass(), methodName, String.class);
        }
        if (method == null) {
            return;
        }
        method.invoke(target, value);
    }

    private Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private void sendGiftCardError(String message, String tenderType) {
        TenderException error = new TenderException();
        error.setFieldValue(TenderException.ExceptionId, "0");
        error.setFieldValue(TenderException.ExceptionType, "0");
        if (StringUtils.isNotBlank(tenderType)) {
            error.setFieldValue(TenderException.TenderType, tenderType);
        }
        error.setFieldValue(TenderException.Message, message);
        ncrController.sendMessage(error);
    }

    private static class PendingPayment {
        private final Tender message;
        private final GiftCardContext context;
        private final String cardNumber;
        private final BigDecimal amount;

        PendingPayment(Tender message, GiftCardContext context, String cardNumber, BigDecimal amount) {
            this.message = message;
            this.context = context;
            this.cardNumber = cardNumber;
            this.amount = amount;
        }
    }

    private static class GiftCardContext {
        private final String paymentCode;
        private final GiftCardManager manager;
        private final MedioPagoBean medioPago;
        private final boolean requiresConfirmation;
        private final String scoTenderType;
        private final boolean autoDetected;

        GiftCardContext(String paymentCode, GiftCardManager manager, MedioPagoBean medioPago,
                boolean requiresConfirmation, String scoTenderType, boolean autoDetected) {
            this.paymentCode = paymentCode;
            this.manager = manager;
            this.medioPago = medioPago;
            this.requiresConfirmation = requiresConfirmation;
            this.scoTenderType = scoTenderType;
            this.autoDetected = autoDetected;
        }
    }

    private static class GiftCardException extends Exception {
        private static final long serialVersionUID = 1L;

        GiftCardException(String message) {
            super(message);
        }

        GiftCardException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
