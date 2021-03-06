package com.rolandopalermo.facturacion.ec.bo.v1_0;

import static com.rolandopalermo.facturacion.ec.common.util.Constants.CREATED;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.rolandopalermo.facturacion.ec.common.exception.ResourceNotFoundException;
import com.rolandopalermo.facturacion.ec.common.exception.VeronicaException;
import com.rolandopalermo.facturacion.ec.common.util.DateUtils;
import com.rolandopalermo.facturacion.ec.common.util.FileUtils;
import com.rolandopalermo.facturacion.ec.common.util.SignerUtils;
import com.rolandopalermo.facturacion.ec.dto.v1_0.ListaComprobantesDTO;
import com.rolandopalermo.facturacion.ec.dto.v1_0.bol.GuiaIdDTO;
import com.rolandopalermo.facturacion.ec.dto.v1_0.bol.GuiaRemisionDTO;
import com.rolandopalermo.facturacion.ec.mapper.bol.GuiaRemisionMapper;
import com.rolandopalermo.facturacion.ec.modelo.guia.GuiaRemision;
import com.rolandopalermo.facturacion.ec.persistence.entity.Bol;
import com.rolandopalermo.facturacion.ec.persistence.entity.Consignee;
import com.rolandopalermo.facturacion.ec.persistence.entity.DigitalCert;
import com.rolandopalermo.facturacion.ec.persistence.repository.BolRepository;
import com.rolandopalermo.facturacion.ec.persistence.repository.DigitalCertRepository;

@Service("bolBO")
public class BolBO {
	
	@Autowired
	private GuiaRemisionMapper guiaMapper;

	@Autowired
	private DigitalCertRepository digitalCertRepository;

	@Autowired
	private BolRepository bolRepository;

	public GuiaIdDTO createBol(GuiaRemisionDTO guiaRemisionDTO) throws ResourceNotFoundException, VeronicaException {
		GuiaRemision guia = guiaMapper.convert(guiaRemisionDTO);
		byte[] xmlContent;
		xmlContent = FileUtils.convertirObjAXML(guia);
		String rucNumber = guia.getInfoTributaria().getRuc();
		List<DigitalCert> certificados = digitalCertRepository.findByOwner(rucNumber);
		if (certificados == null || certificados.isEmpty()) {
			throw new ResourceNotFoundException(String.format("No existe un certificado digital asociado al RUC %S", rucNumber));
		}
		byte[] signedXMLContent = SignerUtils.signXML(xmlContent, certificados.get(0).getDigitalCert(), certificados.get(0).getPassword());
		Bol bol = toEntity(guia, new String(signedXMLContent));
		bolRepository.save(bol);
		GuiaIdDTO guiaIdDTO = new GuiaIdDTO();
		guiaIdDTO.setClaveAcceso(bol.getAccessKey());
		return guiaIdDTO;
	}
	
	public void deleteBol(String claveAcceso) throws ResourceNotFoundException, VeronicaException {
		List<Bol> bols = bolRepository.findByAccessKeyAndIsDeleted(claveAcceso, false);
		if (bols == null || bols.isEmpty()) {
			throw new ResourceNotFoundException(String.format("No se pudo encontrar la guía de remisión con clave de acceso %s", claveAcceso));
		}
		Bol bol = bols.get(0);
		bol.setDeleted(true);
		bolRepository.save(bol);
	}
	
	public String getXML(String claveAcceso) throws ResourceNotFoundException, VeronicaException {
		List<Bol> bols = bolRepository.findByAccessKeyAndIsDeleted(claveAcceso, false);
		if (bols == null || bols.isEmpty()) {
			throw new ResourceNotFoundException(String.format("No se pudo encontrar la guía de remisión con clave de acceso %s", claveAcceso));
		}
		Bol bol = bols.get(0);
		return bol.getXmlContent();
	}
	
	public ListaComprobantesDTO getBolsBySupplier(String supplierId) {
		ListaComprobantesDTO response = new ListaComprobantesDTO();
		List<String> bols = bolRepository.findBySupplierIdAndIsDeleted(supplierId, false);
		List<String> lstAccessKey = Optional.ofNullable(bols)
				.map(List::stream)
				.orElseGet(Stream::empty)
				.collect(Collectors.toList());
		response.setComprobantes(lstAccessKey);
		return response;
	}
	
	public Bol toEntity(GuiaRemision guia, String asXML) throws VeronicaException {
		Bol bol = new Bol();
		bol.setAccessKey(guia.getInfoTributaria().getClaveAcceso());
		bol.setSriVersion(guia.getVersion());
		bol.setXmlContent(asXML);
		bol.setSupplierId(guia.getInfoTributaria().getRuc());
		bol.setShipperRuc(guia.getInfoGuiaRemision().getRucTransportista());
		bol.setRegistrationNumber(guia.getInfoGuiaRemision().getPlaca());
		bol.setIssueDate(DateUtils.getFechaFromStringddMMyyyy(guia.getInfoGuiaRemision().getFechaIniTransporte()));
		bol.setBolNumber(guia.getInfoTributaria().getSecuencial());
		bol.setInternalStatusId(CREATED);
		guia.getDestinatario().forEach(destinatario -> {
			Consignee consignee = new Consignee();
			consignee.setConsignneNumber(destinatario.getIdentificacionDestinatario());
			consignee.setCustomDocNumber(destinatario.getDocAduaneroUnico());
			consignee.setReferenceDocCod(destinatario.getCodDocSustento());
			consignee.setReferenceDocNumber(destinatario.getNumDocSustento());
			consignee.setReferenceDocAuthNumber(destinatario.getNumAutDocSustento());
			bol.addConsignee(consignee);
		});
		return bol;
	}
	
}