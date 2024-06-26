package com.ctrip.xpipe.redis.core.redis.rdb.encoding;

import com.ctrip.xpipe.AbstractTest;
import com.ctrip.xpipe.redis.core.redis.rdb.parser.DefaultRdbParseContext;
import com.ctrip.xpipe.redis.core.redis.rdb.parser.RdbStringParser;
import io.netty.buffer.Unpooled;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author lishanglin
 * date 2022/6/18
 */
public class ListpackTest extends AbstractTest {

    private static final byte[] listpackRdbBytes = new byte[] {0x40, 0x64, 0x64, 0x00, 0x00, 0x00, 0x1e, 0x00, 0x03,
            0x01, 0x00, 0x01, 0x02, 0x01, (byte)0x82, 0x6b, 0x31, 0x03, (byte)0x82, 0x6b, 0x32, 0x03, 0x00, 0x01, 0x02,
            0x01, 0x00, 0x01, 0x00, 0x01, (byte)0x82, 0x76, 0x31, 0x03, (byte)0x82, 0x76, 0x32, 0x03, 0x05, 0x01, 0x00,
            0x01, (byte)0xf1, (byte)0xc4, 0x58, 0x03, 0x00, 0x01, 0x02, 0x01, (byte)0x82, 0x6b, 0x33, 0x03, 0x0a, 0x01,
            (byte)0x82, 0x6b, 0x34, 0x03, (byte)0xf2, (byte)0xff, (byte)0xff, 0x00, 0x04, 0x08, 0x01, 0x00, 0x01,
            (byte)0xf2, 0x40, 0x4b, 0x01, 0x04, 0x00, 0x01, 0x02, 0x01, (byte)0x82, 0x6b, 0x35, 0x03, (byte)0xd0, 0x00,
            0x02, (byte)0x82, 0x6b, 0x36, 0x03, (byte)0xf4, (byte)0xff, 0x7f, (byte)0xc6, (byte)0xa4, 0x7e, (byte)0x8d,
            0x03, 0x00, 0x09, 0x08, 0x01, (byte)0xff};

    @Test
    public void listpackParseTest() {
        RdbStringParser rdbStringParser = new RdbStringParser(new DefaultRdbParseContext());
        byte[] listpackStr = rdbStringParser.read(Unpooled.wrappedBuffer(listpackRdbBytes));

        Listpack listpack = new Listpack(listpackStr);
        for (byte[] val: listpack.convertToList()) {
            logger.info("[listpackParseTest] {}", new String(val));
        }
    }

}
