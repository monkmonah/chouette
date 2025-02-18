package mobi.chouette.ws;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.naming.InitialContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import lombok.extern.log4j.Log4j;
import mobi.chouette.common.Color;
import mobi.chouette.common.Constant;
import mobi.chouette.common.chain.Command;
import mobi.chouette.common.chain.CommandFactory;
import mobi.chouette.common.file.FileStoreFactory;
import mobi.chouette.exchange.importer.CleanRepositoryCommand;
import mobi.chouette.exchange.importer.CleanStopAreaRepositoryCommand;
import mobi.chouette.model.dto.ReferentialInfo;
import mobi.chouette.model.iev.Job;
import mobi.chouette.model.iev.Job.STATUS;
import mobi.chouette.model.iev.Link;
import mobi.chouette.persistence.hibernate.ContextHolder;
import mobi.chouette.service.*;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import static mobi.chouette.exchange.netexprofile.Constant.DATE_TIME_FORMATTER;

@Path("/referentials")
@Log4j
@RequestScoped
public class RestService implements Constant {

	// voir swagger

	private static String api_version_key = "X-ChouetteIEV-Media-Type";
	private static String api_version = "iev.v1.0; format=json";

	@Inject
	JobServiceManager jobServiceManager;

	@Inject
	ReferentialService referentialService;

	@Context
	UriInfo uriInfo;

	// post asynchronous job
	@POST
	@Path("/{ref}/{action}{type:(/[^/]+?)?}")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces({ MediaType.APPLICATION_JSON })
	public Response upload(@PathParam("ref") String referential, @PathParam("action") String action,
						   @PathParam("type") String type, MultipartFormDataInput input) {
		Map<String, InputStream> inputStreamByName = null;
		try {
			log.info(Color.CYAN + "Call upload referential = " + referential + ", action = " + action
					+ (type == null ? "" : ", type = " + type) + Color.NORMAL);



			// Convertir les parametres fournis
			type = parseType(type);
			inputStreamByName = readParts(input);




			// Relayer le service au JobServiceManager
			ResponseBuilder builder = Response.accepted();
			{

				JobService jobService = jobServiceManager.create(referential, action, type, inputStreamByName);

				// Produire la vue
				builder.location(URI.create(MessageFormat.format("{0}/{1}/scheduled_jobs/{2,number,#}", ROOT_PATH,
						jobService.getReferential(), jobService.getId())));
			}
			return builder.build();
		} catch (RequestServiceException e) {
			log.warn("Request Service failed with code = " + e.getRequestCode() , e);
			throw toWebApplicationException(e);
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (WebApplicationException e) {
			log.error(e.getMessage(), e);
			throw e;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		} finally {
			if (inputStreamByName != null) {
				for (InputStream is : inputStreamByName.values()) {
					try {
						is.close();
					} catch (Exception e) {
						Logger.getLogger(RestService.class.getName()).log(Level.SEVERE, e.getMessage(), e);
					}
				}
			}
			log.info(Color.CYAN + "upload returns" + Color.NORMAL);
		}
	}




	private WebApplicationException toWebApplicationException(ServiceException exception) {
		return new WebApplicationException(exception.getMessage(), toWebApplicationCode(exception.getExceptionCode()));
	}

	private Status toWebApplicationCode(ServiceExceptionCode errorCode) {
		switch (errorCode) {
		case INVALID_REQUEST:
			return Status.BAD_REQUEST;
		case INTERNAL_ERROR:
			return Status.INTERNAL_SERVER_ERROR;

		}
		return Status.INTERNAL_SERVER_ERROR;
	}

	private WebApplicationException toWebApplicationException(RequestServiceException exception) {
		return new WebApplicationException(exception.getRequestCode(),
				toWebApplicationCode(exception.getRequestExceptionCode()));
	}

	private Status toWebApplicationCode(RequestExceptionCode errorCode) {
		switch (errorCode) {
		case UNKNOWN_ACTION:
		case DUPPLICATE_OR_MISSING_DATA:
		case DUPPLICATE_PARAMETERS:
		case MISSING_PARAMETERS:
		case UNREADABLE_PARAMETERS:
		case INVALID_PARAMETERS:
		case INVALID_FILE_FORMAT:
		case INVALID_FORMAT:
		case ACTION_TYPE_MISMATCH:
			return Status.BAD_REQUEST;
		case UNKNOWN_REFERENTIAL:
		case UNKNOWN_FILE:
		case UNKNOWN_JOB:
			return Status.NOT_FOUND;
		case SCHEDULED_JOB:
			return Status.METHOD_NOT_ALLOWED;
		case REFERENTIAL_BUSY:
			return Status.CONFLICT;
		case TOO_MANY_ACTIVE_JOBS:
			return Status.SERVICE_UNAVAILABLE;
		}
		return Status.BAD_REQUEST;
	}

	private String parseType(String type) {
		if (type != null && type.startsWith("/")) {
			return type.substring(1);
		}
		return type;
	}

	private Map<String, InputStream> readParts(MultipartFormDataInput input) throws Exception {

		Map<String, InputStream> result = new HashMap<String, InputStream>();

		for (InputPart part : input.getParts()) {
			MultivaluedMap<String, String> headers = part.getHeaders();
			String header = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
			String filename = getFilename(header);

			if (filename == null) {
				throw new ServiceException(ServiceExceptionCode.INVALID_REQUEST, "missing filename in part");
			}
			// protect filename from invalid url chars
			filename = removeSpecialChars(filename);
			result.put(filename, part.getBody(InputStream.class, null));
		}
		return result;
	}

	private String removeSpecialChars(String filename) {
		return filename.replaceAll("[^\\w-_\\.]", "_");
	}


	@POST
	@Path("/{ref}/clean")
	public Response clean(@PathParam("ref") String referential) {
		log.info(Color.CYAN + "Call clean referential = " + referential + Color.NORMAL);
		try {
			ContextHolder.setContext(referential);
			Command command = CommandFactory.create(new InitialContext(), CleanRepositoryCommand.class.getName());
			command.execute(null);
			return Response.ok().build();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		} finally {
			ContextHolder.setContext(null);
			log.info(Color.CYAN + "clean returns" + Color.NORMAL);
		}
	}

    @POST
    @Path("/clean/stop_areas")
    public Response cleanStopAreas() {
        log.info(Color.CYAN + "Call clean stop areas" + Color.NORMAL);
        try {
            Command command = CommandFactory.create(new InitialContext(), CleanStopAreaRepositoryCommand.class.getName());
            command.execute(null);
            return Response.ok().build();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
        } finally {
            ContextHolder.setContext(null);
            log.info(Color.CYAN + "clean returns" + Color.NORMAL);
        }
    }



	// download attached file
	@GET
	@Path("/{ref}/data/{id}/{filepath: .*}")
	@Produces({ MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_JSON })
	public Response download(@PathParam("ref") String referential, @PathParam("id") Long id,
			@PathParam("filepath") String filename) {
		try {
			log.info(Color.CYAN + "Call download referential = " + referential + ", id = " + id + ", filename = "
					+ filename + Color.NORMAL);

			// Retrieve JobService
			ResponseBuilder builder = null;
			MediaType type = null;
			{
				JobService jobService = jobServiceManager.download(referential, id);

				// Build response
				InputStream content = FileStoreFactory.getFileStore().getFileContent(Paths.get(jobService.getPathName(), filename));
				if (content == null){
					throw new RequestServiceException(RequestExceptionCode.UNKNOWN_FILE, "The requested file does not exist: " + filename);
				}
				builder = Response.ok(content);
				builder.header(HttpHeaders.CONTENT_DISPOSITION,
						MessageFormat.format("attachment; filename=\"{0}\"", filename));

				if (FilenameUtils.getExtension(filename).toLowerCase().equals("json")) {
					type = MediaType.APPLICATION_JSON_TYPE;
					builder.header(api_version_key, api_version);
				} else {
					type = MediaType.APPLICATION_OCTET_STREAM_TYPE;
				}

				// cache control
				if (jobService.getStatus().ordinal() >= Job.STATUS.TERMINATED.ordinal()) {
					CacheControl cc = new CacheControl();
					cc.setMaxAge(Integer.MAX_VALUE);
					builder.cacheControl(cc);
				}
			}

			Response result = builder.type(type).build();
			return result;

		} catch (RequestServiceException e) {
			log.warn("Request Service failed with code = " + e.getRequestCode() , e);
			throw toWebApplicationException(e);
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		}
	}

	// jobs listing
	@GET
	@Path("/{ref}/jobs")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response jobs(@PathParam("ref") String referential,
			@DefaultValue("0") @QueryParam("version") final Long version, @QueryParam("action") final String[] action,
			                        @QueryParam("status") final Job.STATUS[] status, @DefaultValue("true") @QueryParam("addActionParameters") boolean addActionParameters) {

		try {
			String refDescription = referential == null ? "all referentials" : "referential = " + referential;
			log.info(Color.CYAN + "Call jobs = " + refDescription + ", action = " + StringUtils.join(action, ',') + ", status = " + StringUtils.join(status, ',') + ", version = "
					         + version + Color.NORMAL);

			// create jobs listing
			List<JobInfo> result = new ArrayList<>();

			// re factor Parameters dependencies
			{
				List<JobService> jobServices = jobServiceManager.jobs(referential, action, version,status);
				for (JobService jobService : jobServices) {
					JobInfo jobInfo = new JobInfo(jobService, true,addActionParameters, uriInfo);
					result.add(jobInfo);
				}
				jobServices.clear();
			}
			// cache control
			ResponseBuilder builder = Response.ok(result);
			builder.header(api_version_key, api_version);
			// CacheControl cc = new CacheControl();
			// cc.setMaxAge(-1);
			// builder.cacheControl(cc);

			return builder.build();
		} catch (RequestServiceException e) {
			log.warn("Request Service failed with code = " + e.getRequestCode() , e);
			throw toWebApplicationException(e);
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		}
	}

	// jobs listing
	@GET
	@Path("/{ref}/last_update_date")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response lastUpdateDate(@PathParam("ref") String referential) {

		try {
			log.info(Color.CYAN + "Call last update date for " + referential + Color.NORMAL);
			String lastUpdateDate = DATE_TIME_FORMATTER.format(referentialService.getLastUpdateTimestamp(referential));
			log.info(Color.CYAN + "Last update date for " + referential + " is "  + lastUpdateDate + Color.NORMAL);
			ResponseBuilder builder = Response.ok(lastUpdateDate);
			builder.header(api_version_key, api_version);
			return builder.build();
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		}
	}

	// jobs listing for all referentials
	@GET
	@Path("/jobs")
	@Produces({MediaType.APPLICATION_JSON})
	public Response jobs(@DefaultValue("0") @QueryParam("version") final Long version, @QueryParam("action") final String[] action,
			                        @QueryParam("status") final Job.STATUS[] status, @DefaultValue("true") @QueryParam("addActionParameters") boolean addActionParameters) {
		return jobs(null, version, action, status, addActionParameters);
	}

	// view scheduled job
	@GET
	@Path("/{ref}/scheduled_jobs/{id}")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response scheduledJob(@PathParam("ref") String referential, @PathParam("id") Long id) {
		try {
			log.info(Color.CYAN + "Call scheduledJob referential = " + referential + ", id = " + id + Color.NORMAL);

			Response result = null;
			ResponseBuilder builder = null;

			{
				JobService jobService = jobServiceManager.scheduledJob(referential, id);

				// build response
				if (jobService.getStatus().ordinal() <= STATUS.STARTED.ordinal()) {
					JobInfo info = new JobInfo(jobService, true, uriInfo);
					builder = Response.ok(info);
				} else {
					builder = Response.seeOther(URI.create(MessageFormat.format(
							"/{0}/{1}/terminated_jobs/{2,number,#}", ROOT_PATH, jobService.getReferential(),
							jobService.getId())));
				}

				// add links
				for (Link link : jobService.getJob().getLinks()) {
					URI uri = URI.create(uriInfo.getBaseUri() + link.getHref());
					builder.link(URI.create(uri.toASCIIString()), link.getRel());
				}
			}

			builder.header(api_version_key, api_version);
			result = builder.build();
			return result;

		} catch (RequestServiceException e) {
			log.warn("Request Service failed with code = " + e.getRequestCode() , e);
			throw toWebApplicationException(e);
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		}
	}

	// cancel job
	@DELETE
	@Path("/{ref}/scheduled_jobs/{id}")
	public Response cancel(@PathParam("ref") String referential, @PathParam("id") Long id, String dummy) {
		try {
			// dummy uses when sender call url with content (prevent a
			// NullPointerException)
			log.info(Color.CYAN + "Call cancel referential = " + referential + ", id = " + id + Color.NORMAL);

			Response result = null;

			JobService jobService = jobServiceManager.cancel(referential, id);

			ResponseBuilder builder = Response.ok();
			result = builder.build();

				// add links
				for (Link link : jobService.getJob().getLinks()) {
					URI uri = URI.create(uriInfo.getBaseUri() + link.getHref());
					builder.link(URI.create(uri.toASCIIString()), link.getRel());
				}

			builder.header(api_version_key, api_version);

			return result;
		} catch (RequestServiceException e) {
			log.warn("Request Service failed with code = " + e.getRequestCode() , e);
			throw toWebApplicationException(e);
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		}
	}

	// download report
	@GET
	@Path("/{ref}/terminated_jobs/{id}")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response terminatedJob(@PathParam("ref") String referential, @PathParam("id") Long id) {
		try {
			log.info(Color.CYAN + "Call terminatedJob referential = " + referential + ", id = " + id + Color.NORMAL);

			ResponseBuilder builder = null;
			{
				JobService jobService = jobServiceManager.terminatedJob(referential, id);

				JobInfo info = new JobInfo(jobService, true, uriInfo);
				builder = Response.ok(info);

				// cache control
				CacheControl cc = new CacheControl();
				cc.setMaxAge(Integer.MAX_VALUE);
				builder.cacheControl(cc);

				// add links
				for (Link link : jobService.getJob().getLinks()) {
					URI uri = URI.create(uriInfo.getBaseUri() + link.getHref());
					builder.link(URI.create(uri.toASCIIString()), link.getRel());
				}
			}

			builder.header(api_version_key, api_version);
			return builder.build();

		} catch (RequestServiceException e) {
			log.warn("Request Service failed with code = " + e.getRequestCode() , e);
			throw toWebApplicationException(e);
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		}
	}

	// delete report
	@DELETE
	@Path("/{ref}/terminated_jobs/{id}")
	public Response remove(@PathParam("ref") String referential, @PathParam("id") Long id, String dummy) {
		try {
			log.info(Color.CYAN + "Call remove referential = " + referential + ", id = " + id + ", dummy = " + dummy
					+ Color.NORMAL);

			// dummy uses when sender call url with content (prevent a
			// NullPointerException)
			Response result = null;

			{
				jobServiceManager.remove(referential, id);

				// build response
				ResponseBuilder builder = Response.ok("deleted");
				builder.header(api_version_key, api_version);
				result = builder.build();
			}

			return result;

		} catch (RequestServiceException e) {
			log.warn("Request Service failed with code = " + e.getRequestCode() , e);
			throw toWebApplicationException(e);
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		}
	}

	//create referential
	@POST
	@Consumes({MediaType.APPLICATION_JSON})
	@Path("/create")
	public Response create(ReferentialInfo referentialInfo) {
		log.info("Creating referential " + referentialInfo.getDataspaceName());
		try {
			boolean created = referentialService.createReferential(referentialInfo);
			if (created) {
				return Response.ok().header(api_version_key, api_version).build();
			} else {
				return Response.status(Status.CONFLICT).header(api_version_key, api_version).build();
			}

		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new WebApplicationException("INTERNAL_ERROR: " + ex.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
		}
	}

	//update referential
	@POST
	@Consumes({MediaType.APPLICATION_JSON})
	@Path("/update")
	public Response update(ReferentialInfo referentialInfo) {
		log.info("Updating referential " + referentialInfo.getDataspaceName());
		try {
			referentialService.updateReferential(referentialInfo);
			return Response.ok().header(api_version_key, api_version).build();
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new WebApplicationException("INTERNAL_ERROR: " + ex.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
		}
	}

	//delete referential database schema and drop associated jobs
	@DELETE
	@Consumes({MediaType.APPLICATION_JSON})
	@Path("/delete")
	public Response delete(ReferentialInfo referentialInfo) {
		log.info("Deleting referential " + referentialInfo.getDataspaceName());
		try {
			referentialService.deleteReferential(referentialInfo);
			jobServiceManager.drop(referentialInfo.getSchemaName());
			return Response.ok().header(api_version_key, api_version).build();
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new WebApplicationException("INTERNAL_ERROR: " + ex.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
		}
	}

	// delete referential
	@DELETE
	@Path("/{ref}/jobs")
	public Response drop(@PathParam("ref") String referential, String dummy) {
		try {
			log.info(Color.CYAN + "Call drop referential = " + referential + ", dummy = " + dummy + Color.NORMAL);

			// dummy uses when sender call url with content (prevent a
			// NullPointerException)
			Response result = null;

			jobServiceManager.drop(referential);

			// build response
			ResponseBuilder builder = Response.ok("");
			builder.header(api_version_key, api_version);
			result = builder.build();

			return result;
		} catch (RequestServiceException e) {
			log.warn("Request Service failed with code = " + e.getRequestCode() , e);
			throw toWebApplicationException(e);
		} catch (ServiceException e) {
			log.error("Service failed with code = " + e.getCode() , e);
			throw toWebApplicationException(e);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new WebApplicationException("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR);
		}
	}

	private String getFilename(String header) {
		String result = null;

		if (header != null) {
			for (String token : header.split(";")) {
				if (token.trim().startsWith("filename")) {
					result = token.substring(token.indexOf('=') + 1).trim().replace("\"", "");
					break;
				}
			}
		}
		return result;
	}

}
