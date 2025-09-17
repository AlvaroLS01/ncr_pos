package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.List;

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
            ensureGiftCardDefaults(giftCard);
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
            Class<?> restClass = Class.forName("com.comerzzia.api.rest.client.fidelizados.FidelizadosRest");
            Class<?> requestClass = null;
            try {
                requestClass = Class.forName("com.comerzzia.api.rest.client.fidelizados.ConsultarFidelizadoRequestRest");
            } catch (ClassNotFoundException ignored) {
                requestClass = null;
            }

            Object response = invokeGiftCardEndpoint(restClass, requestClass, cardNumber);
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
        }
    }

    private Object invokeGiftCardEndpoint(Class<?> restClass, Class<?> requestClass, String cardNumber)
            throws GiftCardException {
        String apiKey = null;
        try {
            apiKey = variablesServices.getVariableAsString(Variables.WEBSERVICES_APIKEY);
        } catch (Exception e) {
            log.debug("invokeGiftCardEndpoint() - Unable to resolve API key", e);
        }

        String uidActividad = null;
        if (sesion != null && sesion.getAplicacion() != null) {
            uidActividad = sesion.getAplicacion().getUidActividad();
        }

        if (requestClass != null) {
            Method method = findMethod(restClass, "getTarjetaRegalo", requestClass);
            if (method != null) {
                Object request = instantiateGiftCardRequest(requestClass, cardNumber, apiKey, uidActividad);
                return invokeGiftCardMethod(method, new Object[] { request });
            }
        }

        for (Method method : restClass.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !"getTarjetaRegalo".equals(method.getName())) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (allStringParameters(parameterTypes)) {
                Object response = tryInvokeWithStringArguments(method, cardNumber, apiKey, uidActividad);
                if (response != null) {
                    return response;
                }
            }
        }

        throw new GiftCardException(I18N.getTexto("Servicio de tarjetas regalo no disponible."));
    }

    private Object invokeGiftCardMethod(Method method, Object[] args) throws GiftCardException {
        try {
            return method.invoke(null, args);
        } catch (IllegalAccessException e) {
            throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
            throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), cause);
        }
    }

    private Object instantiateGiftCardRequest(Class<?> requestClass, String cardNumber, String apiKey,
            String uidActividad) throws GiftCardException {
        try {
            Object instance = instantiateWithDefaults(requestClass);

            applyStringProperty(instance,
                    new String[] { "setNumeroTarjeta", "setNumTarjeta", "setNumeroTarjetaRegalo", "setTarjeta" },
                    cardNumber);
            applyStringProperty(instance, new String[] { "setUidActividad", "setUidActividadServicio" },
                    uidActividad);
            applyStringProperty(instance, new String[] { "setApiKey" }, apiKey);

            return instance;
        } catch (ReflectiveOperationException e) {
            throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
        }
    }

    private Object instantiateWithDefaults(Class<?> clazz) throws ReflectiveOperationException {
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Object[] args = buildDefaultArguments(ctor.getParameterTypes());
                if (args == null) {
                    continue;
                }
                try {
                    return ctor.newInstance(args);
                } catch (InvocationTargetException | InstantiationException | IllegalAccessException ex) {
                    // try next constructor
                }
            }
            throw e;
        }
    }

    private Object[] buildDefaultArguments(Class<?>[] parameterTypes) {
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> param = parameterTypes[i];
            if (!param.isPrimitive()) {
                args[i] = null;
            } else if (boolean.class.equals(param)) {
                args[i] = Boolean.FALSE;
            } else if (byte.class.equals(param)) {
                args[i] = (byte) 0;
            } else if (short.class.equals(param)) {
                args[i] = (short) 0;
            } else if (int.class.equals(param)) {
                args[i] = 0;
            } else if (long.class.equals(param)) {
                args[i] = 0L;
            } else if (float.class.equals(param)) {
                args[i] = 0f;
            } else if (double.class.equals(param)) {
                args[i] = 0d;
            } else if (char.class.equals(param)) {
                args[i] = '\0';
            } else {
                return null;
            }
        }
        return args;
    }

    private void applyStringProperty(Object target, String[] methodNames, String value)
            throws IllegalAccessException, InvocationTargetException {
        if (target == null || StringUtils.isBlank(value) || methodNames == null) {
            return;
        }
        for (String name : methodNames) {
            Method setter = findMethod(target.getClass(), name, String.class);
            if (setter != null) {
                setter.invoke(target, value);
                return;
            }
        }
    }

    private boolean allStringParameters(Class<?>[] parameterTypes) {
        if (parameterTypes.length == 0) {
            return false;
        }
        for (Class<?> type : parameterTypes) {
            if (!String.class.equals(type)) {
                return false;
            }
        }
        return true;
    }

    private Object tryInvokeWithStringArguments(Method method, String cardNumber, String apiKey, String uidActividad)
            throws GiftCardException {
        List<Object[]> candidates = buildStringArgumentCandidates(method.getParameterCount(), cardNumber, apiKey,
                uidActividad);
        for (Object[] candidate : candidates) {
            try {
                return method.invoke(null, candidate);
            } catch (IllegalArgumentException e) {
                continue;
            } catch (IllegalAccessException e) {
                throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
                throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), cause);
            }
        }
        return null;
    }

    private List<Object[]> buildStringArgumentCandidates(int length, String cardNumber, String apiKey,
            String uidActividad) {
        List<Object[]> candidates = new ArrayList<>();
        if (length <= 0) {
            return candidates;
        }

        if (length == 1) {
            candidates.add(new Object[] { cardNumber });
            if (apiKey != null) {
                candidates.add(new Object[] { apiKey });
            }
            if (uidActividad != null) {
                candidates.add(new Object[] { uidActividad });
            }
            return candidates;
        }

        if (length == 2) {
            candidates.add(new Object[] { cardNumber, apiKey });
            candidates.add(new Object[] { cardNumber, uidActividad });
            candidates.add(new Object[] { apiKey, uidActividad });
            candidates.add(new Object[] { uidActividad, apiKey });
            candidates.add(new Object[] { apiKey, cardNumber });
            candidates.add(new Object[] { uidActividad, cardNumber });
            return candidates;
        }

        if (length == 3) {
            Object[] values = new Object[] { cardNumber, apiKey, uidActividad };
            int[][] permutations = new int[][] { { 0, 1, 2 }, { 0, 2, 1 }, { 1, 0, 2 }, { 1, 2, 0 }, { 2, 0, 1 },
                    { 2, 1, 0 } };
            for (int[] permutation : permutations) {
                Object[] candidate = new Object[length];
                for (int i = 0; i < length; i++) {
                    candidate[i] = values[permutation[i]];
                }
                candidates.add(candidate);
            }
            return candidates;
        }

        Object[] fallback = new Object[length];
        Arrays.fill(fallback, null);
        if (length > 0) {
            fallback[0] = cardNumber;
        }
        if (length > 1) {
            fallback[1] = apiKey;
        }
        if (length > 2) {
            fallback[2] = uidActividad;
        }
        candidates.add(fallback);
        return candidates;
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
        ensureGiftCardDefaults(bean);
        try {
            return bean.getSaldoTotal();
        } catch (Exception ex) {
            BigDecimal saldo = bean.getSaldo() != null ? bean.getSaldo() : BigDecimal.ZERO;
            BigDecimal provisional = bean.getSaldoProvisional() != null ? bean.getSaldoProvisional()
                    : BigDecimal.ZERO;
            return saldo.add(provisional);
        }
    }

    private void ensureGiftCardDefaults(GiftCardBean bean) {
        if (bean.getSaldo() == null) {
            bean.setSaldo(BigDecimal.ZERO);
        }
        if (bean.getSaldoProvisional() == null) {
            bean.setSaldoProvisional(BigDecimal.ZERO);
        }
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