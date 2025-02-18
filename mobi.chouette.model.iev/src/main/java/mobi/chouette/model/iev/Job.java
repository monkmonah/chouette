package mobi.chouette.model.iev;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@EqualsAndHashCode(of = { "id" })
@ToString(exclude = { "parametersAsString" })
@Entity
@Table(name = "jobs",indexes = {@Index(name = "i_referential_type_status", columnList = "referential,type,status")})
@Data
public class Job implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "jobs_seq", sequenceName = "jobs_seq", allocationSize = 20)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "jobs_seq")
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "referential")
	private String referential;

	@Column(name = "action")
	private String action;

	@Column(name = "type")
	private String type;

	@Deprecated
	@Column(name = "filename")
	private String dataFilename;

	@Column(name = "input_filename")
	private String inputFilename;

	@Column(name = "output_filename")
	private String outputFilename;

	@Column(name = "created")
	private LocalDateTime created;

	@Column(name = "started")
	private LocalDateTime started;

	@Column(name = "updated")
	private LocalDateTime updated;

	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private STATUS status;

	@Column(name = "parameters", columnDefinition = "TEXT")
	private String parametersAsString;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "links", joinColumns = @JoinColumn(name = "job_id"),
			indexes = {@Index(name = "i_links_job_id",  columnList="job_id", unique = false)}
    )
	private List<Link> links = new ArrayList<Link>();

	public Job() {
		super();
		status = STATUS.CREATED;
		created = LocalDateTime.now();
		updated = created;
	}

	public Job(String referential, String action, String type) {
		this();
		this.referential = referential;
		this.action = action;
		this.type = type;
	}

	@XmlType
	@XmlEnum(String.class)
	public enum STATUS implements java.io.Serializable {
		CREATED, RESCHEDULED, SCHEDULED, STARTED, TERMINATED, CANCELED, ABORTED, DELETED;

		public static List<STATUS> getCompletedStatuses() {
			return Arrays.stream(STATUS.values()).filter(status -> status.ordinal() > STARTED.ordinal()).collect(Collectors.toList());
		}
	}
}
