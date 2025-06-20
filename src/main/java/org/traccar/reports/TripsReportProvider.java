/*
 * Copyright 2016 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.reports;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import org.traccar.config.Config;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.DeviceReportSection;
import org.traccar.reports.model.TripReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

import jakarta.inject.Inject;

public class TripsReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final Storage storage;

    // Cache for fonts to avoid recreating them
    private static Font arabicFont = null;
    private static Font defaultFont = null;

    @Inject
    public TripsReportProvider(Config config, ReportUtils reportUtils, Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.storage = storage;
    }

    // Helper method to create Arabic-compatible font from embedded resources
    private Font createArabicFont(int size, int style) {
        try {
            // Load the embedded Noto Sans Arabic font from resources
            InputStream fontStream = getClass().getResourceAsStream("/fonts/NotoSansArabic-Regular.ttf");
            if (fontStream != null) {
                byte[] fontBytes = fontStream.readAllBytes();
                fontStream.close();

                // Create BaseFont from embedded font data
                BaseFont baseFont = BaseFont.createFont(
                    "NotoSansArabic-Regular.ttf",
                    BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED,
                    true,
                    fontBytes,
                    null
                );
                return new Font(baseFont, size, style);
            } else {
                System.err.println("Arabic font resource not found, using fallback");
            }
        } catch (Exception e) {
            // Log the error but don't fail
            System.err.println("Failed to load Arabic font: " + e.getMessage());
        }

        // Enhanced fallback: Try to create a Unicode-compatible font
        try {
            BaseFont baseFont = BaseFont.createFont(
                BaseFont.HELVETICA,
                BaseFont.CP1252,
                BaseFont.NOT_EMBEDDED
            );
            return new Font(baseFont, size, style);
        } catch (Exception e) {
            System.err.println("Failed to create Unicode fallback font: " + e.getMessage());
        }

        // Final fallback to default font
        return new Font(Font.FontFamily.HELVETICA, size, style);
    }

    // Helper method to get cached Arabic font
    private Font getArabicFont(int size, int style) {
        if (arabicFont == null) {
            arabicFont = createArabicFont(size, style);
        }
        return arabicFont;
    }

    // Helper method to get cached default font
    private Font getDefaultFont(int size, int style) {
        if (defaultFont == null) {
            defaultFont = new Font(Font.FontFamily.HELVETICA, size, style);
        }
        return defaultFont;
    }

    // Helper method to detect if text contains Arabic characters
    private boolean containsArabic(String text) {
        if (text == null) {
            return false;
        }
        for (char c : text.toCharArray()) {
            // Check for Arabic Unicode blocks
            if ((c >= 0x0600 && c <= 0x06FF)
                || (c >= 0x0750 && c <= 0x077F)
                || (c >= 0x08A0 && c <= 0x08FF)
                || (c >= 0xFB50 && c <= 0xFDFF)
                || (c >= 0xFE70 && c <= 0xFEFF)) {
                return true;
            }
        }
        return false;
    }

    // Helper method to check if a character is Arabic
    private boolean isArabicChar(char c) {
        return (c >= 0x0600 && c <= 0x06FF)
            || (c >= 0x0750 && c <= 0x077F)
            || (c >= 0x08A0 && c <= 0x08FF)
            || (c >= 0xFB50 && c <= 0xFDFF)
            || (c >= 0xFE70 && c <= 0xFEFF);
    }

    // Helper method to split text into Arabic and Latin segments
    private java.util.List<TextSegment> segmentText(String text) {
        java.util.List<TextSegment> segments = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            return segments;
        }

        StringBuilder currentSegment = new StringBuilder();
        boolean currentIsArabic = false;
        boolean firstChar = true;

        for (char c : text.toCharArray()) {
            boolean charIsArabic = isArabicChar(c);
            
            if (firstChar) {
                currentIsArabic = charIsArabic;
                firstChar = false;
            }

            // If the character type changes, save the current segment and start a new one
            if (charIsArabic != currentIsArabic && currentSegment.length() > 0) {
                segments.add(new TextSegment(currentSegment.toString(), currentIsArabic));
                currentSegment = new StringBuilder();
                currentIsArabic = charIsArabic;
            }

            currentSegment.append(c);
        }

        // Add the last segment
        if (currentSegment.length() > 0) {
            segments.add(new TextSegment(currentSegment.toString(), currentIsArabic));
        }

        return segments;
    }

    // Helper class to represent text segments
    private static class TextSegment {
        private final String text;
        private final boolean isArabic;

        public TextSegment(String text, boolean isArabic) {
            this.text = text;
            this.isArabic = isArabic;
        }

        public String getText() {
            return text;
        }

        public boolean isArabic() {
            return isArabic;
        }
    }

    // Helper method to create address cell with proper font and alignment for mixed text
    private PdfPCell createAddressCell(String address) {
        String processedAddress = (address != null) ? address.trim() : "";

        try {
            if (!containsArabic(processedAddress)) {
                // Pure Latin text - use default font
                Font font = getDefaultFont(10, Font.NORMAL);
                PdfPCell cell = new PdfPCell(new Phrase(processedAddress, font));
                cell.setHorizontalAlignment(Element.ALIGN_LEFT);
                return cell;
            }

            // Mixed or Arabic text - handle with segmentation
            java.util.List<TextSegment> segments = segmentText(processedAddress);
            
            if (segments.isEmpty()) {
                // Empty text
                Font font = getDefaultFont(10, Font.NORMAL);
                PdfPCell cell = new PdfPCell(new Phrase("", font));
                return cell;
            }

            // Create a paragraph with mixed chunks
            Paragraph paragraph = new Paragraph();
            
            for (TextSegment segment : segments) {
                if (segment.isArabic()) {
                    // Use Arabic font for Arabic segments
                    Font arabicFont = getArabicFont(10, Font.NORMAL);
                    Phrase arabicPhrase = new Phrase(segment.getText(), arabicFont);
                    paragraph.add(arabicPhrase);
                } else {
                    // Use default font for Latin segments
                    Font latinFont = getDefaultFont(10, Font.NORMAL);
                    Phrase latinPhrase = new Phrase(segment.getText(), latinFont);
                    paragraph.add(latinPhrase);
                }
            }

            // Create cell with RTL support for mixed content
            PdfPCell cell = new PdfPCell();
            cell.addElement(paragraph);
            
            // If text contains Arabic, set RTL direction and Arabic options
            if (containsArabic(processedAddress)) {
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                cell.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
                cell.setArabicOptions(ColumnText.AR_LIG);
                paragraph.setAlignment(Element.ALIGN_RIGHT);
            } else {
                cell.setHorizontalAlignment(Element.ALIGN_LEFT);
                paragraph.setAlignment(Element.ALIGN_LEFT);
            }

            return cell;

        } catch (Exception e) {
            // If anything fails, create a simple cell to ensure PDF generation doesn't break
            System.err.println("Error creating address cell: " + e.getMessage());
            Font font = getDefaultFont(10, Font.NORMAL);
            PdfPCell cell = new PdfPCell(new Phrase(processedAddress, font));
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
            return cell;
        }
    }

    public Collection<TripReportItem> getObjects(
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        ArrayList<TripReportItem> result = new ArrayList<>();
        for (Device device: DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            result.addAll(reportUtils.detectTripsAndStops(device, from, to, TripReportItem.class));
        }
        return result;
    }

    public void getExcel(OutputStream outputStream,
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException, IOException, DocumentException {
        reportUtils.checkPeriodLimit(from, to);

        ArrayList<DeviceReportSection> devicesTrips = new ArrayList<>();
        for (Device device: DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            Collection<TripReportItem> trips = reportUtils.detectTripsAndStops(device, from, to, TripReportItem.class);
            DeviceReportSection deviceTrips = new DeviceReportSection();
            deviceTrips.setDeviceName(device.getName());
            if (device.getGroupId() > 0) {
                Group group = storage.getObject(Group.class, new Request(
                        new Columns.All(), new Condition.Equals("id", device.getGroupId())));
                if (group != null) {
                    deviceTrips.setGroupName(group.getName());
                }
            }
            deviceTrips.setObjects(trips);
            devicesTrips.add(deviceTrips);
        }

        Document document = new Document(PageSize.A4.rotate());
        PdfWriter.getInstance(document, outputStream);
        document.open();

        // Add title
        Font titleFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD);
        Paragraph title = new Paragraph("Trips Report", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(20);
        document.add(title);

        // Add date range
        Font dateFont = new Font(Font.FontFamily.HELVETICA, 12);
        Paragraph dateRange = new Paragraph(
            String.format("From: %s to %s", from, to),
            dateFont
        );
        dateRange.setAlignment(Element.ALIGN_CENTER);
        dateRange.setSpacingAfter(20);
        document.add(dateRange);

        // Add trips data
        for (DeviceReportSection deviceTrips : devicesTrips) {
            // Add device section
            Font deviceFont = new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD);
            String deviceName = deviceTrips.getDeviceName();
            if (deviceTrips.getGroupName() != null) {
                deviceName += " (" + deviceTrips.getGroupName() + ")";
            }
            Paragraph deviceTitle = new Paragraph(deviceName, deviceFont);
            deviceTitle.setSpacingBefore(20);
            deviceTitle.setSpacingAfter(10);
            document.add(deviceTitle);

            // Create table for trips
            PdfPTable table = new PdfPTable(8);
            table.setWidthPercentage(100);

            // Add table headers
            String[] headers = {"Start Time", "End Time", "Duration", "Distance",
                    "Average Speed", "Max Speed", "Start Address", "End Address"};
            for (String header : headers) {
                table.addCell(new PdfPCell(new Phrase(header,
                        new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD))));
            }

            // Add trip data
            for (TripReportItem trip : (Collection<TripReportItem>) deviceTrips.getObjects()) {
                table.addCell(new PdfPCell(new Phrase(trip.getStartTime().toString())));
                table.addCell(new PdfPCell(new Phrase(trip.getEndTime().toString())));
                table.addCell(new PdfPCell(new Phrase(String.format("%.2f hours", trip.getDuration() / 3600000.0))));
                table.addCell(new PdfPCell(new Phrase(String.format("%.2f km", trip.getDistance() / 1000.0))));
                table.addCell(new PdfPCell(new Phrase(String.format("%.2f km/h", trip.getAverageSpeed()))));
                table.addCell(new PdfPCell(new Phrase(String.format("%.2f km/h", trip.getMaxSpeed()))));
                // Use Arabic-compatible address cells
                table.addCell(createAddressCell(trip.getStartAddress()));
                table.addCell(createAddressCell(trip.getEndAddress()));
            }
            document.add(table);
        }

        document.close();
    }

}