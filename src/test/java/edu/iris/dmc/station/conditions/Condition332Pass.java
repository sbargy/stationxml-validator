package edu.iris.dmc.station.conditions;

import java.io.InputStream;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import edu.iris.dmc.DocumentMarshaller;
import edu.iris.dmc.fdsn.station.model.Channel;
import edu.iris.dmc.fdsn.station.model.FDSNStationXML;
import edu.iris.dmc.fdsn.station.model.Network;
import edu.iris.dmc.fdsn.station.model.Station;
import edu.iris.dmc.station.RuleEngineServiceTest;
import edu.iris.dmc.station.restrictions.ChannelCodeRestriction;
import edu.iris.dmc.station.restrictions.ChannelTypeRestriction;
import edu.iris.dmc.station.restrictions.Restriction;
import edu.iris.dmc.station.rules.Message;

public class Condition332Pass {

	private FDSNStationXML theDocument;

	@Before
	public void init() throws Exception {

	}

	@Test
	public void fail() throws Exception {
		try (InputStream is = RuleEngineServiceTest.class.getClassLoader().getResourceAsStream("P1_332.xml")) {
			theDocument = DocumentMarshaller.unmarshal(is);
			Restriction[] restrictions = new Restriction[] { new ChannelCodeRestriction(), new ChannelTypeRestriction() };

			Network n = theDocument.getNetwork().get(0);
			Station s = n.getStations().get(0);
			Channel c = s.getChannels().get(0);

			OrientationCondition condition = new OrientationCondition(true, "", restrictions);
 
			Message result = condition.evaluate(c); 
			System.out.println(result);
			Assert.assertTrue(result instanceof edu.iris.dmc.station.rules.Success);
		}

	}

	@Test
	public void pass() throws Exception {
		try (InputStream is = RuleEngineServiceTest.class.getClassLoader().getResourceAsStream("pass.xml")) {
			theDocument = DocumentMarshaller.unmarshal(is);

			Network n = theDocument.getNetwork().get(0);
			Station s = n.getStations().get(0);

			EpochRangeCondition condition = new EpochRangeCondition(true, "");

			Message result = condition.evaluate(s);
			Assert.assertTrue(result instanceof edu.iris.dmc.station.rules.Success);
		}

	}
}

