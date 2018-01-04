/*
 * Copyright (C) 2009-2018 Slava Semushin <slava.semushin@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package ru.mystamps.web.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import org.slf4j.Logger;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.security.access.prepost.PreAuthorize;

import lombok.RequiredArgsConstructor;

import ru.mystamps.web.Db.SeriesImportRequestStatus;
import ru.mystamps.web.controller.event.ParsingFailed;
import ru.mystamps.web.dao.SeriesImportDao;
import ru.mystamps.web.dao.dto.AddSeriesParsedDataDbDto;
import ru.mystamps.web.dao.dto.ImportRequestDto;
import ru.mystamps.web.dao.dto.ImportRequestFullInfo;
import ru.mystamps.web.dao.dto.ImportRequestInfo;
import ru.mystamps.web.dao.dto.ImportSeriesDbDto;
import ru.mystamps.web.dao.dto.ParsedDataDto;
import ru.mystamps.web.service.dto.AddSeriesDto;
import ru.mystamps.web.service.dto.RawParsedDataDto;
import ru.mystamps.web.service.dto.RequestImportDto;
import ru.mystamps.web.support.spring.security.HasAuthority;

// it complains on "Request id must be non null"
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@RequiredArgsConstructor
public class SeriesImportServiceImpl implements SeriesImportService {
	
	private final Logger log;
	private final SeriesImportDao seriesImportDao;
	private final SeriesService seriesService;
	private final SeriesInfoExtractorService extractorService;
	private final ApplicationEventPublisher eventPublisher;
	
	@Override
	@Transactional
	@PreAuthorize(HasAuthority.IMPORT_SERIES)
	@SuppressWarnings({ "PMD.NPathComplexity", "PMD.ModifiedCyclomaticComplexity" })
	public Integer addRequest(RequestImportDto dto, Integer userId) {
		Validate.isTrue(dto != null, "DTO must be non null");
		Validate.isTrue(dto.getUrl() != null, "URL must be non null");
		Validate.isTrue(userId != null, "Current user id must be non null");
		
		ImportSeriesDbDto importRequest = new ImportSeriesDbDto();
		importRequest.setStatus(SeriesImportRequestStatus.UNPROCESSED);
		
		try {
			String encodedUrl = new URI(dto.getUrl()).toASCIIString();
			importRequest.setUrl(encodedUrl);
		} catch (URISyntaxException ex) {
			throw new RuntimeException(ex); // NOPMD: AvoidThrowingRawExceptionTypes
		}
		
		Date now = new Date();
		importRequest.setUpdatedAt(now);
		importRequest.setRequestedAt(now);
		importRequest.setRequestedBy(userId);
		
		Integer id = seriesImportDao.add(importRequest);
		
		log.info("Request #{} for importing series from '{}' has been created", id, dto.getUrl());
		
		return id;
	}
	
	@Override
	@Transactional
	@PreAuthorize(HasAuthority.IMPORT_SERIES)
	public Integer addSeries(AddSeriesDto dto, Integer requestId, Integer userId) {
		Integer seriesId = seriesService.add(dto, userId, false);
		
		Date now = new Date();
		
		seriesImportDao.setSeriesIdAndChangeStatus(
			requestId,
			seriesId,
			SeriesImportRequestStatus.PARSING_SUCCEEDED,
			SeriesImportRequestStatus.IMPORT_SUCCEEDED,
			now
		);
		
		return seriesId;
	}
	
	@Override
	@Transactional
	public void changeStatus(Integer requestId, String oldStatus, String newStatus) {
		Validate.isTrue(requestId != null, "Request id must be non null");
		Validate.isTrue(StringUtils.isNotBlank(oldStatus), "Old status must be non-blank");
		Validate.isTrue(StringUtils.isNotBlank(newStatus), "New status must be non-blank");
		Validate.isTrue(!oldStatus.equals(newStatus), "Statuses must be different");
		
		Date now = new Date();
		
		seriesImportDao.changeStatus(requestId, now, oldStatus, newStatus);
	}
	
	@Override
	@Transactional(readOnly = true)
	@PreAuthorize(HasAuthority.IMPORT_SERIES)
	public ImportRequestDto findById(Integer requestId) {
		Validate.isTrue(requestId != null, "Request id must be non null");
		
		return seriesImportDao.findById(requestId);
	}
	
	@Override
	@Transactional
	public void saveDownloadedContent(Integer requestId, String content) {
		Validate.isTrue(requestId != null, "Request id must be non null");
		Validate.isTrue(StringUtils.isNotBlank(content), "Content must be non-blank");
		
		Date now = new Date();
		
		seriesImportDao.addRawContent(requestId, now, now, content);
		
		log.info("Request #{}: page were downloaded ({} characters)", requestId, content.length());
		
		changeStatus(
			requestId,
			SeriesImportRequestStatus.UNPROCESSED,
			SeriesImportRequestStatus.DOWNLOADING_SUCCEEDED
		);
	}
	
	@Override
	@Transactional(readOnly = true)
	public String getDownloadedContent(Integer requestId) {
		Validate.isTrue(requestId != null, "Request id must be non null");
		
		return seriesImportDao.findRawContentByRequestId(requestId);
	}
	
	@Override
	@Transactional
	public void saveParsedData(Integer requestId, RawParsedDataDto data) {
		Validate.isTrue(requestId != null, "Request id must be non null");
		Validate.isTrue(data != null, "Parsed data must be non null");
		
		AddSeriesParsedDataDbDto seriesParsedData = new AddSeriesParsedDataDbDto();
		seriesParsedData.setImageUrl(data.getImageUrl());
		Date now = new Date();
		seriesParsedData.setCreatedAt(now);
		seriesParsedData.setUpdatedAt(now);
		
		List<Integer> categoryIds = extractorService.extractCategory(data.getCategoryName());
		Integer categoryId = getFirstElement(categoryIds);
		seriesParsedData.setCategoryId(categoryId);
		
		List<Integer> countryIds = extractorService.extractCountry(data.getCountryName());
		Integer countryId = getFirstElement(countryIds);
		seriesParsedData.setCountryId(countryId);
		
		Integer releaseYear = extractorService.extractReleaseYear(data.getReleaseYear());
		seriesParsedData.setReleaseYear(releaseYear);
		
		Integer quantity = extractorService.extractQuantity(data.getQuantity());
		seriesParsedData.setQuantity(quantity);
		
		Boolean perforated = extractorService.extractPerforated(data.getPerforated());
		seriesParsedData.setPerforated(perforated);
		
		// IMPORTANT: don't add code that modifies database above this line!
		// @todo #684 Series import: add integration test
		//  for the case when parsed value don't match database
		if (!seriesParsedData.hasAtLeastOneFieldFilled()) {
			eventPublisher.publishEvent(new ParsingFailed(this, requestId));
			return;
		}
		
		seriesImportDao.addParsedContent(requestId, seriesParsedData);
		
		log.info("Request #{}: page were parsed ({})", requestId, seriesParsedData);
		
		changeStatus(
			requestId,
			SeriesImportRequestStatus.DOWNLOADING_SUCCEEDED,
			SeriesImportRequestStatus.PARSING_SUCCEEDED
		);
	}
	
	@Override
	@Transactional(readOnly = true)
	public ParsedDataDto getParsedData(Integer requestId, String lang) {
		Validate.isTrue(requestId != null, "Request id must be non null");
		
		return seriesImportDao.findParsedDataByRequestId(requestId, lang);
	}
	
	@Override
	@Transactional(readOnly = true)
	@PreAuthorize(HasAuthority.IMPORT_SERIES)
	public ImportRequestInfo findRequestInfo(Integer seriesId) {
		Validate.isTrue(seriesId != null, "Series id must be non null");
		
		return seriesImportDao.findRequestInfo(seriesId);
	}
	
	@Override
	@Transactional(readOnly = true)
	@PreAuthorize(HasAuthority.IMPORT_SERIES)
	public List<ImportRequestFullInfo> findAll() {
		return seriesImportDao.findAll();
	}
	
	private static Integer getFirstElement(List<Integer> list) {
		if (list.isEmpty()) {
			return null;
		}
		return list.get(0);
	}
	
}
