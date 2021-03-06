package com.ethlo.time;

/*-
 * #%L
 * Internet Time Utility
 * %%
 * Copyright (C) 2017 Morten Haraldsen (ethlo)
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.Date;

/**
 * Extreme level of optimization to squeeze every CPU cycle. 
 * 
 * @author ethlo - Morten Haraldsen
 */
public class FastInternetDateTimeUtil extends AbstractInternetDateTimeUtil implements W3cDateTimeUtil
{
    private final StdJdkInternetDateTimeUtil delegate = new StdJdkInternetDateTimeUtil();
    
    private static final char PLUS = '+';
    private static final char MINUS = '-';
    private static final char DATE_SEPARATOR = '-';
    private static final char TIME_SEPARATOR = ':';
    private static final char SEPARATOR_UPPER = 'T';
    private static final char SEPARATOR_LOWER = 't';
    private static final char FRACTION_SEPARATOR = '.';
    private static final char ZULU_UPPER = 'Z';
    private static final char ZULU_LOWER = 'z';
    private static final int[] widths = new int[]{100_000_000, 10_000_000, 1_000_000, 100_000, 10_000, 1_000, 100, 10, 1};

    //private boolean allowMilitaryTimezone;
    //private boolean allowMissingTimezone;

    
    public FastInternetDateTimeUtil()
    {
        super(false);
    }
    
    @Override
    public OffsetDateTime parse(String s)
    {
        return OffsetDateTime.class.cast(doParseLenient(s, OffsetDateTime.class));
    }
    
    private void assertPositionContains(char[] chars, int offset, char... expected)
    {
        boolean found = false;
        for (char e : expected)
        {
            if (chars[offset] == e)
            {
                found = true;
                break;
            }
        }
        if (! found)
        {
            throw new DateTimeException("Expected character " + Arrays.toString(expected) 
                + " at position " + (offset + 1) + " '" + new String(chars) + "'");
        }
    }

    private ZoneOffset parseTz(char[] chars, int offset)
    {
        final int left = chars.length - offset;
        if (chars[offset] == ZULU_UPPER || chars[offset] == ZULU_LOWER)
        {
            assertNoMoreChars(chars, offset);
            return ZoneOffset.UTC;
        }
        
        if (left != 6)
        {
            throw new DateTimeException("Invalid timezone offset: " + new String(chars, offset, left));
        }
        
        final char sign = chars[offset];
        int hours = LimitedCharArrayIntegerUtil.parsePositiveInt(chars, offset + 1, offset + 3);
        int minutes = LimitedCharArrayIntegerUtil.parsePositiveInt(chars, offset + 4, offset + 4 + 2);
        if (sign == MINUS)
        {
            hours = -hours;
        }
        else if (sign != PLUS)
        {
            throw new DateTimeException("Invalid character starting at position " + offset + 1);
        }
        
        if (! allowUnknownLocalOffsetConvention())
        {
            if (sign == MINUS && hours == 0 && minutes == 0)
            {
                super.failUnknownLocalOffsetConvention();
            }
        }
        
        return ZoneOffset.ofHoursMinutes(hours, minutes);
    }

    private void assertNoMoreChars(char[] chars, int lastUsed)
    {
        if (chars.length > lastUsed + 1)
        {
            throw new DateTimeException("Unparsed data from offset " + lastUsed + 1);
        }
    }

    @Override
    public String formatUtc(OffsetDateTime date, int fractionDigits)
    {
        return formatUtc(date, Field.SECOND, fractionDigits);
    }
    
    @Override
    public String formatUtc(OffsetDateTime date, Field lastIncluded, int fractionDigits)
    {
        assertMaxFractionDigits(fractionDigits);
        final LocalDateTime utc = LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
        
        final char[] buf = new char[31];
        
        // Date
        LimitedCharArrayIntegerUtil.toString(utc.getYear(), buf, 0, 4);
        if (lastIncluded == Field.YEAR)
        {
            return finish(buf, 4);  
        }
        buf[4] = DATE_SEPARATOR;
        LimitedCharArrayIntegerUtil.toString(utc.getMonthValue(), buf, 5, 2);
        if (lastIncluded == Field.MONTH)
        {
            return finish(buf, 7);  
        }
        buf[7] = DATE_SEPARATOR;
        LimitedCharArrayIntegerUtil.toString(utc.getDayOfMonth(), buf, 8, 2);
        if (lastIncluded == Field.DAY)
        {
            return finish(buf, 10);  
        }
        
        // T separator
        buf[10] = SEPARATOR_UPPER;
        
        // Time
        LimitedCharArrayIntegerUtil.toString(utc.getHour(), buf, 11, 2);
        buf[13] = TIME_SEPARATOR;
        LimitedCharArrayIntegerUtil.toString(utc.getMinute(), buf, 14, 2);
        if (lastIncluded == Field.MINUTE)
        {
            return finish(buf, 16);  
        }
        buf[16] = TIME_SEPARATOR;
        LimitedCharArrayIntegerUtil.toString(utc.getSecond(), buf, 17, 2);
        
        // Second fractions
        final boolean hasFractionDigits = fractionDigits > 0;
        if (hasFractionDigits)
        {
            buf[19] = FRACTION_SEPARATOR;
            addFractions(buf, fractionDigits, utc.getNano());
        }
        
        // Add time-zone 'Z'
        buf[(hasFractionDigits ? 20 + fractionDigits : 19)] = ZULU_UPPER;
        final int length = hasFractionDigits ? 21 + fractionDigits : 20;
        
        return finish(buf, length);
    }

    private String finish(char[] buf, int length)
    {
        return new String(buf, 0, length);
    }

    private void addFractions(char[] buf, int fractionDigits, int nano)
    {
        final double d = widths[fractionDigits - 1];
        LimitedCharArrayIntegerUtil.toString((int)(nano / d), buf, 20, fractionDigits);
    }

    @Override
    public String formatUtc(Date date)
    {
        return formatUtc(OffsetDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC), 3);
    }

    @Override
    public String format(Date date, String timezone)
    {
        return delegate.format(date, timezone);
    }

    @Override
    public boolean isValid(String dateTime)
    {
        try
        {
            parse(dateTime);
            return true;
        }
        catch (DateTimeException exc)
        {
            return false;
        }
    }

    @Override
    public String formatUtcMilli(OffsetDateTime date)
    {
        return formatUtc(date, 3);
    }

    @Override
    public String formatUtcMicro(OffsetDateTime date)
    {
        return formatUtc(date, 6);
    }

    @Override
    public String formatUtcNano(OffsetDateTime date)
    {
        return formatUtc(date, 9);
    }
    
    @Override
    public String formatUtc(OffsetDateTime date)
    {
        return formatUtc(date, 0);
    }

    @Override
    public String formatUtcMilli(Date date)
    {
        return formatUtcMilli(OffsetDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC));
    }

    @Override
    public String format(Date date, String timezone, int fractionDigits)
    {
        return delegate.format(date, timezone, fractionDigits);
    }

    @Override
    public Temporal parseLenient(String s)
    {
        return doParseLenient(s, null);
    }
    
    @Override
    public <T extends Temporal> T parseLenient(String s, Class<T> type)
    {
        return type.cast(doParseLenient(s, type));
    }
    
    public <T extends Temporal> Temporal doParseLenient(String s, Class<T> type)
    {
        if (s == null || s.isEmpty())
        {
            return null;
        }
        
        final Field maxRequired = type == null ? null : Field.valueOf(type);
        final char[] chars = s.toCharArray();

        // Date portion
        
        // YEAR
        final int year = LimitedCharArrayIntegerUtil.parsePositiveInt(chars, 0, 4);
        if (maxRequired == Field.YEAR)
        {
            return Year.of(year);
        }
        
        // MONTH
        assertPositionContains(chars, 4, DATE_SEPARATOR);
        final int month = LimitedCharArrayIntegerUtil.parsePositiveInt(chars, 5, 7);
        if (maxRequired == Field.MONTH)
        {
            return type.cast(YearMonth.of(year, month));
        }
        
        // DAY
        assertPositionContains(chars, 7, DATE_SEPARATOR);
        final int day = LimitedCharArrayIntegerUtil.parsePositiveInt(chars, 8, 10);
        if (maxRequired == Field.DAY || chars.length == 10)
        {
            return LocalDate.of(year, month, day);
        }
        
        // *** Time starts ***//

        // HOURS
        assertPositionContains(chars, 10, SEPARATOR_UPPER, SEPARATOR_LOWER);
        final int hour = LimitedCharArrayIntegerUtil.parsePositiveInt(chars, 11, 13);
        
        // MINUTES
        assertPositionContains(chars, 13, TIME_SEPARATOR);
        final int minute = LimitedCharArrayIntegerUtil.parsePositiveInt(chars, 14, 16);
        if (maxRequired == Field.MINUTE || chars.length == 16)
        {
            return LocalDate.of(year, month, day);
        }
        
        // SECONDS or TIMEZONE
        switch (chars[16])
        {
            // We have more granularity, keep going
            case TIME_SEPARATOR:
                return seconds(year, month, day, hour, minute, chars);
                
            case PLUS:
            case MINUS:
            case ZULU_UPPER:
            case ZULU_LOWER:
                final ZoneOffset zoneOffset = parseTz(chars, 16);
                return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, zoneOffset);
                
            default:
              assertPositionContains(chars, 16, TIME_SEPARATOR, PLUS, MINUS, ZULU_UPPER);
        }
        throw new DateTimeException(new String(chars));
    }
    
    private OffsetDateTime seconds(int year, int month, int day, int hour, int minute, char[] chars)
    {
        final int second = LimitedCharArrayIntegerUtil.parsePositiveInt(chars, 17, 19);
        
        // From here the specification is more lenient
        final int remaining = chars.length - 19;
        
        ZoneOffset offset = null;
        int fractions = 0;
        
        if (remaining == 1 && (chars[19] == ZULU_UPPER || chars[19] == ZULU_LOWER))
        {
            // Do nothing we are done
            offset = ZoneOffset.UTC;
            assertNoMoreChars(chars, 19);
        }
        else if (remaining >= 1 && chars[19] == FRACTION_SEPARATOR)
        {
            // We have fractional seconds
            final int idx = LimitedCharArrayIntegerUtil.indexOfNonDigit(chars, 20);
            if (idx != -1)
            {
                // We have an end of fractions
                final int len = idx - 20;
                fractions = LimitedCharArrayIntegerUtil.parsePositiveInt(chars, 20, idx);
                if (len == 1) {fractions = fractions * 100_000_000;}
                if (len == 2) {fractions = fractions * 10_000_000;}
                if (len == 3) {fractions = fractions * 1_000_000;}
                if (len == 4) {fractions = fractions * 100_000;}
                if (len == 5) {fractions = fractions * 10_000;}
                if (len == 6) {fractions = fractions * 1_000;}
                if (len == 7) {fractions = fractions * 100;}
                if (len == 8) {fractions = fractions * 10;}
                offset = parseTz(chars, idx);
            }
            else
            {
                offset = parseTz(chars, 20);
            }
        }
        else if (remaining >= 1 && (chars[19] == PLUS || chars[19] == MINUS))
        {
            // No fractional sections
            offset = parseTz(chars, 19);
        }
        else if (remaining == 0)
        {
            throw new DateTimeException("Unexpected end of expression at position 19 '" + new String(chars) + "'");
        }
        else
        {
            throw new DateTimeException("Unexpected character at position 19:" + chars[19]);
        }
        
        return OffsetDateTime.of(year, month, day, hour, minute, second, fractions, offset);
    }
}
