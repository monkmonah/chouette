package mobi.chouette.exchange.metadata;
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */


import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Calendar;

import mobi.chouette.common.TimeUtil;
import org.apache.commons.io.FileUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.testng.Assert;
import org.testng.Reporter;
import org.testng.annotations.BeforeGroups;
import org.testng.annotations.Test;


/**
 * 
 * @author michel
 */

public class TextMetadataTest
{
   protected TextFileWriter fileWriter;
	private  File d = new File("target/referential/test");


   @BeforeGroups  (groups = { "text" })
   protected void setUp() throws Exception
   {
      fileWriter = new TextFileWriter();
		if (d.exists())
			try {
				org.apache.commons.io.FileUtils.deleteDirectory(d);
			} catch (IOException e) {
				e.printStackTrace();
			}
		d.mkdirs();

   }
   
   
   private Metadata initMetadata() throws MalformedURLException
   {
      Calendar date = Calendar.getInstance();
      date.set(2015,Calendar.JANUARY,15,13,00);
      Calendar start = Calendar.getInstance();
      start.set(2014,Calendar.DECEMBER,01,13,00);
      Calendar end = Calendar.getInstance();
      end.set(2015,Calendar.MARCH,31,13,00);
      Metadata data = new Metadata();
      data.setCreator("the creator");
      data.setDate(TimeUtil.toLocalDateTime(date));
      data.setPublisher("the publisher");
      data.setFormat("the format");
      data.getSpatialCoverage().update(3.45678, 45.78965);
      data.getTemporalCoverage().update(TimeUtil.toLocalDate(start), TimeUtil.toLocalDate(end));
      data.setTitle("the title");
      data.setRelation(new URL("http://the.relation.com"));
      return data;
      
   }

   @Test(groups = { "text" }, description = "text with description")
   public void verifyTextMetadataWithoutResource() throws Exception
   {
      Metadata data = initMetadata();
      data.setDescription("the description");

      fileWriter.writePlainFile(data, Paths.get(d.toURI()));
      
      File f = new File(d,"metadata_chouette.txt");
      Assert.assertTrue(f.exists(), "File metadata_chouette.txt should exist");
      String s = FileUtils.readFileToString(f);
      Reporter.log(s);
      String model = FileUtils.readFileToString(new File("src/test/data/metadata/metadata_chouette_1.txt"));
      Assert.assertTrue(s.equals(model), "metadata must be as expected in metadata_chouette_1.txt");
      
   }
   
   
   @Test(groups = { "text" }, description = "text with resources")
   public void verifyTextMetadataWithResources() throws Exception
   {
      Metadata data = initMetadata();
      data.setDescription("the description");
      data.getResources().add(new Metadata.Resource(null, "ligne 1"));
      data.getResources().add(new Metadata.Resource("réseau 1", "ligne 2"));
      data.getResources().add(new Metadata.Resource("fichier1.xml", "réseau 2", "ligne 3"));
      data.getResources().add(new Metadata.Resource("fichier2.xml", null, "ligne 4"));

      fileWriter.writePlainFile(data, Paths.get(d.toURI()));
      
      File f = new File(d,"metadata_chouette.txt");
      Assert.assertTrue(f.exists(), "File metadata_chouette.txt should exist");
      String s = FileUtils.readFileToString(f);
      Reporter.log(s);
      String model = FileUtils.readFileToString(new File("src/test/data/metadata/metadata_chouette_2.txt"));
      Assert.assertTrue(s.equals(model), "metadata must be as expected in metadata_chouette_2.txt");
      
   }

   
   
   

}
