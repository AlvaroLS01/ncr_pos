package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;

@Lazy(false)
@Service
@Primary
public class AmetllerItemsManager extends ItemsManager {

    private static final String DESCRIPCION_DESCUENTO_25 = "Descuento del 25% aplicado";

    @Override
    protected ItemSold lineaTicketToItemSold(LineaTicket linea) {
        ItemSold itemSold = super.lineaTicketToItemSold(linea);

        if (!(ticketManager instanceof AmetllerScoTicketManager)) {
            return itemSold;
        }

        AmetllerScoTicketManager ametllerScoTicketManager = (AmetllerScoTicketManager) ticketManager;

        if (!ametllerScoTicketManager.isLineaConDescuento25(linea)) {
            return itemSold;
        }

        BigDecimal precioConDto = linea.getPrecioTotalConDto();
        if (precioConDto != null) {
            itemSold.setFieldIntValue(ItemSold.Price, precioConDto);
        }

        BigDecimal importeConDto = linea.getImporteTotalConDto();
        if (importeConDto != null) {
            itemSold.setFieldIntValue(ItemSold.ExtendedPrice, importeConDto);
        }

        ItemSold discount = itemSold.getDiscountApplied();
        if (discount == null) {
            return itemSold;
        }

        BigDecimal importeSinDto = linea.getImporteTotalSinDto();
        BigDecimal descuentoCalculado = BigDecimal.ZERO;

        if (importeSinDto != null && importeConDto != null) {
            descuentoCalculado = importeSinDto.subtract(importeConDto);
        }

        if ((descuentoCalculado == null || descuentoCalculado.compareTo(BigDecimal.ZERO) <= 0)
                && linea.getImporteTotalPromociones() != null) {
            descuentoCalculado = linea.getImporteTotalPromociones();
        }

        if (descuentoCalculado == null) {
            return itemSold;
        }

        descuentoCalculado = descuentoCalculado.setScale(2, RoundingMode.HALF_UP);

        if (descuentoCalculado.compareTo(BigDecimal.ZERO) <= 0) {
            return itemSold;
        }

        if (importeConDto == null && importeSinDto != null) {
            importeConDto = importeSinDto.subtract(descuentoCalculado);
        }

        if (importeConDto != null) {
            discount.setFieldIntValue(ItemSold.Price, importeConDto);
        }

        discount.setFieldValue(ItemSold.ItemNumber,
                String.valueOf(linea.getIdLinea() + PROMOTIONS_FIRST_ITEM_ID));
        discount.setFieldIntValue(ItemSold.DiscountAmount, descuentoCalculado);
        discount.setFieldValue(ItemSold.AssociatedItemNumber, String.valueOf(linea.getIdLinea()));
        discount.setFieldValue(ItemSold.RewardLocation, "3");
        discount.setFieldValue(ItemSold.ShowRewardPoints, "1");
        discount.setFieldValue(ItemSold.DiscountDescription, DESCRIPCION_DESCUENTO_25);

        discount.setFieldValue(ItemSold.Description, DESCRIPCION_DESCUENTO_25);

        return itemSold;
    }
}
