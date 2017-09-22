package com.eprint.print;

import javax.print.attribute.*;
import javax.print.attribute.standard.*;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.ServiceUIFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;
import javax.print.event.PrintServiceAttributeListener;

import com.eprint.print.converter.PrintServiceAttributeMap;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import java.util.HashSet;


public class IPPPrintService implements PrintService, AttributeUpdater{

	public static final boolean debugPrint;
	private static final String debugPrefix = "IPPPrintService>> ";
	protected static void debug_println(String str) {
		if (debugPrint) {
			System.out.println(str);
		}
	}

	private static final String FORCE_PIPE_PROP = "sun.print.ippdebug";

	/**
	 * List of all PrintServiceAttributes.  This is used
	 * for looping through all the IPP attribute name.
	 */
	private static final IPPServiceAttribute[] serviceAttributes = IPPServiceAttribute.values(); 
		
	
	static {
		String debugStr =
				System.getProperty(FORCE_PIPE_PROP);
		debugPrint = "true".equalsIgnoreCase(debugStr);
	};
	

	private String printer;
	private URI    myURI;
	private URL    myURL;
	transient private ServiceNotifier notifier = null;

	private static int MAXCOPIES = 1000;
	private static short MAX_ATTRIBUTE_LENGTH = 255;

	private CUPSPrinter cps;
	private HttpURLConnection urlConnection = null;
	private DocFlavor[] supportedDocFlavors;
	private Class<?>[] supportedCats;
	private MediaTray[] mediaTrays;
	private MediaSizeName[] mediaSizeNames;
	private CustomMediaSizeName[] customMediaSizeNames;
	private int defaultMediaIndex;
	private boolean isCupsPrinter;
	private boolean init;
	private Boolean isPS;
	private Map<String, AttributeClass> getAttMap;
	private boolean pngImagesAdded = false;
	private boolean gifImagesAdded = false;
	private boolean jpgImagesAdded = false;
	
	private final AttributeLookupService m_attrLookupService;


	/**
	 * IPP Status Codes
	 */
	private static final byte STATUSCODE_SUCCESS = 0x00;

	/**
	 * IPP Group Tags.  Each tag is used once before the first attribute
	 * of that group.
	 */
	// operation attributes group
	private static final byte GRPTAG_OP_ATTRIBUTES = 0x01;
	// job attributes group
	private static final byte GRPTAG_JOB_ATTRIBUTES = 0x02;
	// printer attributes group
	private static final byte GRPTAG_PRINTER_ATTRIBUTES = 0x04;
	// used as the last tag in an IPP message.
	private static final byte GRPTAG_END_ATTRIBUTES = 0x03;

	/**
	 * IPP Operation codes
	 */
	// gets the attributes for a printer
	public static final String OP_GET_ATTRIBUTES = "000B";
	// gets the default printer
	public static final String OP_CUPS_GET_DEFAULT = "4001";
	// gets the list of printers
	public static final String OP_CUPS_GET_PRINTERS = "4002";


	/**
	 * List of all PrintRequestAttributes.  This is used
	 * for looping through all the IPP attribute name.
	 */
	private static Object[] printReqAttribDefault = {
			Chromaticity.COLOR,
			new Copies(1),
			Fidelity.FIDELITY_FALSE,
			Finishings.NONE,
			//new JobHoldUntil(new Date()),
			//new JobImpressions(0),
			//JobImpressions,
			//JobKOctets,
			//JobMediaSheets,
			new JobName("", Locale.getDefault()),
			//JobPriority,
			JobSheets.NONE,
			(Media)MediaSizeName.NA_LETTER,
			//MediaPrintableArea.class, // not an IPP attribute
			//MultipleDocumentHandling.SINGLE_DOCUMENT,
			new NumberUp(1),
			OrientationRequested.PORTRAIT,
			new PageRanges(1),
			//PresentationDirection,
			// CUPS does not supply printer-resolution attribute
			//new PrinterResolution(300, 300, PrinterResolution.DPI),
			//PrintQuality.NORMAL,
			new RequestingUserName("", Locale.getDefault()),
			//SheetCollate.UNCOLLATED, //CUPS has no sheet collate?
			Sides.ONE_SIDED,
	};



	/**
	 * List of DocFlavors, grouped based on matching mime-type.
	 * NOTE: For any change in the predefined DocFlavors, it must be reflected
	 * here also.
	 */
	// PDF DocFlavors
	private static DocFlavor[] appPDF = {
			DocFlavor.BYTE_ARRAY.PDF,
			DocFlavor.INPUT_STREAM.PDF,
			DocFlavor.URL.PDF
	};

	// Postscript DocFlavors
	private static DocFlavor[] appPostScript = {
			DocFlavor.BYTE_ARRAY.POSTSCRIPT,
			DocFlavor.INPUT_STREAM.POSTSCRIPT,
			DocFlavor.URL.POSTSCRIPT
	};

	// Autosense DocFlavors
	private static DocFlavor[] appOctetStream = {
			DocFlavor.BYTE_ARRAY.AUTOSENSE,
			DocFlavor.INPUT_STREAM.AUTOSENSE,
			DocFlavor.URL.AUTOSENSE
	};

	// Text DocFlavors
	private static DocFlavor[] textPlain = {
			DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_8,
			DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_16,
			DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_16BE,
			DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_16LE,
			DocFlavor.BYTE_ARRAY.TEXT_PLAIN_US_ASCII,
			DocFlavor.INPUT_STREAM.TEXT_PLAIN_UTF_8,
			DocFlavor.INPUT_STREAM.TEXT_PLAIN_UTF_16,
			DocFlavor.INPUT_STREAM.TEXT_PLAIN_UTF_16BE,
			DocFlavor.INPUT_STREAM.TEXT_PLAIN_UTF_16LE,
			DocFlavor.INPUT_STREAM.TEXT_PLAIN_US_ASCII,
			DocFlavor.URL.TEXT_PLAIN_UTF_8,
			DocFlavor.URL.TEXT_PLAIN_UTF_16,
			DocFlavor.URL.TEXT_PLAIN_UTF_16BE,
			DocFlavor.URL.TEXT_PLAIN_UTF_16LE,
			DocFlavor.URL.TEXT_PLAIN_US_ASCII,
			DocFlavor.CHAR_ARRAY.TEXT_PLAIN,
			DocFlavor.STRING.TEXT_PLAIN,
			DocFlavor.READER.TEXT_PLAIN
	};

	private static DocFlavor[] textPlainHost = {
			DocFlavor.BYTE_ARRAY.TEXT_PLAIN_HOST,
			DocFlavor.INPUT_STREAM.TEXT_PLAIN_HOST,
			DocFlavor.URL.TEXT_PLAIN_HOST
	};

	// JPG DocFlavors
	private static DocFlavor[] imageJPG = {
			DocFlavor.BYTE_ARRAY.JPEG,
			DocFlavor.INPUT_STREAM.JPEG,
			DocFlavor.URL.JPEG
	};

	// GIF DocFlavors
	private static DocFlavor[] imageGIF = {
			DocFlavor.BYTE_ARRAY.GIF,
			DocFlavor.INPUT_STREAM.GIF,
			DocFlavor.URL.GIF
	};

	// PNG DocFlavors
	private static DocFlavor[] imagePNG = {
			DocFlavor.BYTE_ARRAY.PNG,
			DocFlavor.INPUT_STREAM.PNG,
			DocFlavor.URL.PNG
	};

	// HTML DocFlavors
	private  static DocFlavor[] textHtml = {
			DocFlavor.BYTE_ARRAY.TEXT_HTML_UTF_8,
			DocFlavor.BYTE_ARRAY.TEXT_HTML_UTF_16,
			DocFlavor.BYTE_ARRAY.TEXT_HTML_UTF_16BE,
			DocFlavor.BYTE_ARRAY.TEXT_HTML_UTF_16LE,
			DocFlavor.BYTE_ARRAY.TEXT_HTML_US_ASCII,
			DocFlavor.INPUT_STREAM.TEXT_HTML_UTF_8,
			DocFlavor.INPUT_STREAM.TEXT_HTML_UTF_16,
			DocFlavor.INPUT_STREAM.TEXT_HTML_UTF_16BE,
			DocFlavor.INPUT_STREAM.TEXT_HTML_UTF_16LE,
			DocFlavor.INPUT_STREAM.TEXT_HTML_US_ASCII,
			DocFlavor.URL.TEXT_HTML_UTF_8,
			DocFlavor.URL.TEXT_HTML_UTF_16,
			DocFlavor.URL.TEXT_HTML_UTF_16BE,
			DocFlavor.URL.TEXT_HTML_UTF_16LE,
			DocFlavor.URL.TEXT_HTML_US_ASCII,
			// These are not handled in UnixPrintJob so commenting these
			// for now.
			/*
        DocFlavor.CHAR_ARRAY.TEXT_HTML,
        DocFlavor.STRING.TEXT_HTML,
        DocFlavor.READER.TEXT_HTML,
			 */
	};

	private  static DocFlavor[] textHtmlHost = {
			DocFlavor.BYTE_ARRAY.TEXT_HTML_HOST,
			DocFlavor.INPUT_STREAM.TEXT_HTML_HOST,
			DocFlavor.URL.TEXT_HTML_HOST,
	};


	// PCL DocFlavors
	private static DocFlavor[] appPCL = {
			DocFlavor.BYTE_ARRAY.PCL,
			DocFlavor.INPUT_STREAM.PCL,
			DocFlavor.URL.PCL
	};

	// List of all DocFlavors, used in looping
	// through all supported mime-types
	private static Object[] allDocFlavors = {
			appPDF, appPostScript, appOctetStream,
			textPlain, imageJPG, imageGIF, imagePNG,
			textHtml, appPCL,
	};


	IPPPrintService(String name, URL url) {
		if ((name == null) || (url == null)){
			throw new IllegalArgumentException("null uri or printer name");
		}
		printer = name;
		supportedDocFlavors = null;
		supportedCats = null;
		mediaSizeNames = null;
		customMediaSizeNames = null;
		mediaTrays = null;
		myURL = url;
		cps = null;
		isCupsPrinter = false;
		init = false;
		defaultMediaIndex = -1;

		String host = myURL.getHost();
		if (host!=null && host.equals(CUPSPrinter.getServer())) {
			isCupsPrinter = true;
			try {
				myURI =  new URI("ipp://"+host+
						"/printers/"+printer);
				debug_println(debugPrefix+"IPPPrintService myURI : "+myURI);
			} catch (java.net.URISyntaxException e) {
				throw new IllegalArgumentException("invalid url");
			}
		}
		
		m_attrLookupService = AttributeLookupServiceProvider.create(PrintServiceAttributeMap.getMap(printer, myURI));
	}


	IPPPrintService(String name, String uriStr, boolean isCups) {
		if ((name == null) || (uriStr == null)){
			throw new IllegalArgumentException("null uri or printer name");
		}
		printer = name;
		supportedDocFlavors = null;
		supportedCats = null;
		mediaSizeNames = null;
		customMediaSizeNames = null;
		mediaTrays = null;
		cps = null;
		init = false;
		defaultMediaIndex = -1;
		try {
			myURL =
					new URL(uriStr.replaceFirst("ipp", "http"));
		} catch (Exception e) {
			IPPPrintService.debug_println(debugPrefix+
					" IPPPrintService, myURL="+
					myURL+" Exception= "+
					e);
			throw new IllegalArgumentException("invalid url");
		}

		isCupsPrinter = isCups;
		try {
			myURI =  new URI(uriStr);
			debug_println(debugPrefix+"IPPPrintService myURI : "+myURI);
		} catch (java.net.URISyntaxException e) {
			throw new IllegalArgumentException("invalid uri");
		}
		
		m_attrLookupService = AttributeLookupServiceProvider.create(PrintServiceAttributeMap.getMap(printer, myURI));
	}


	/*
	 * Initialize mediaSizeNames, mediaTrays and other attributes.
	 * Media size/trays are initialized to non-null values, may be 0-length
	 * array.
	 * NOTE: Must be called from a synchronized block only.
	 */
	private void initAttributes() {
		if (!init) {
			if ((urlConnection = getIPPConnection(myURL)) == null) {
				debug_println(debugPrefix+"initAttributes, NULL urlConnection ");
				return;
			}

			// get all supported attributes through IPP
			opGetAttributes();
			urlConnection.disconnect();
			init = true;
		}
	}


	public DocPrintJob createPrintJob() {
		SecurityManager security = System.getSecurityManager();
		if (security != null) {
			security.checkPrintJobAccess();
		}
		// REMIND: create IPPPrintJob
		//return new UnixPrintJob(this);
		return null;
	}


	public synchronized Object
	getSupportedAttributeValues(Class<? extends Attribute> category,
			DocFlavor flavor,
			AttributeSet attributes)
	{
		if (category == null) {
			throw new NullPointerException("null category");
		}
		if (!Attribute.class.isAssignableFrom(category)) {
			throw new IllegalArgumentException(category +
					" does not implement Attribute");
		}
		if (flavor != null) {
			if (!isDocFlavorSupported(flavor)) {
				throw new IllegalArgumentException(flavor +
						" is an unsupported flavor");
			} else if (isAutoSense(flavor)) {
				return null;
			}

		}

		if (!isAttributeCategorySupported(category)) {
			return null;
		}

		/* Test if the flavor is compatible with the attributes */
		if (!isDestinationSupported(flavor, attributes)) {
			return null;
		}

		initAttributes();

		/* Test if the flavor is compatible with the category */
		if ((category == Copies.class) ||
				(category == CopiesSupported.class)) {
			if (flavor == null ||
					!(flavor.equals(DocFlavor.INPUT_STREAM.POSTSCRIPT) ||
							flavor.equals(DocFlavor.URL.POSTSCRIPT) ||
							flavor.equals(DocFlavor.BYTE_ARRAY.POSTSCRIPT))) {
				CopiesSupported cs = new CopiesSupported(1, MAXCOPIES);
				AttributeClass attribClass = (getAttMap != null) ?
						(AttributeClass)getAttMap.get(cs.getName()) : null;
						if (attribClass != null) {
							int[] range = attribClass.getIntRangeValue();
							cs = new CopiesSupported(range[0], range[1]);
						}
						return cs;
			} else {
				return null;
			}
		} else  if (category == Chromaticity.class) {
			if (flavor == null ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PAGEABLE) ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PRINTABLE) ||
					!isIPPSupportedImages(flavor.getMimeType())) {
				Chromaticity[]arr = new Chromaticity[1];
				arr[0] = Chromaticity.COLOR;
				return (arr);
			} else {
				return null;
			}
		} else if (category == Destination.class) {
			if (flavor == null ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PAGEABLE) ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PRINTABLE)) {
				try {
					return new Destination((new File("out.ps")).toURI());
				} catch (SecurityException se) {
					try {
						return new Destination(new URI("file:out.ps"));
					} catch (URISyntaxException e) {
						return null;
					}
				}
			}
			return null;
		} else if (category == Fidelity.class) {
			Fidelity []arr = new Fidelity[2];
			arr[0] = Fidelity.FIDELITY_FALSE;
			arr[1] = Fidelity.FIDELITY_TRUE;
			return arr;
		} else if (category == Finishings.class) {
			AttributeClass attribClass = (getAttMap != null) ?
					(AttributeClass)getAttMap.get("finishings-supported")
					: null;
					if (attribClass != null) {
						int[] finArray = attribClass.getArrayOfIntValues();
						if ((finArray != null) && (finArray.length > 0)) {
							Finishings[] finSup = new Finishings[finArray.length];
							for (int i=0; i<finArray.length; i++) {
								finSup[i] = Finishings.NONE;
								Finishings[] fAll = (Finishings[])
										(new ExtFinishing(100)).getAll();
								for (int j=0; j<fAll.length; j++) {
									if (finArray[i] == fAll[j].getValue()) {
										finSup[i] = fAll[j];
										break;
									}
								}
							}
							return finSup;
						}
					}
		} else if (category == JobName.class) {
			return new JobName("Java Printing", null);
		} else if (category == JobSheets.class) {
			JobSheets arr[] = new JobSheets[2];
			arr[0] = JobSheets.NONE;
			arr[1] = JobSheets.STANDARD;
			return arr;

		} else if (category == Media.class) {
			Media[] allMedia = new Media[mediaSizeNames.length+
			                             mediaTrays.length];

			for (int i=0; i<mediaSizeNames.length; i++) {
				allMedia[i] = mediaSizeNames[i];
			}

			for (int i=0; i<mediaTrays.length; i++) {
				allMedia[i+mediaSizeNames.length] = mediaTrays[i];
			}

			if (allMedia.length == 0) {
				allMedia = new Media[1];
				allMedia[0] = (Media)getDefaultAttributeValue(Media.class);
			}

			return allMedia;
		} else if (category == MediaPrintableArea.class) {
			MediaPrintableArea[] mpas = null;
			if (cps != null) {
				mpas = cps.getMediaPrintableArea();
			}

			if (mpas == null) {
				mpas = new MediaPrintableArea[1];
				mpas[0] = (MediaPrintableArea)
						getDefaultAttributeValue(MediaPrintableArea.class);
			}

			if ((attributes == null) || (attributes.size() == 0)) {
				ArrayList<MediaPrintableArea> printableList =
						new ArrayList<MediaPrintableArea>();

				for (int i=0; i<mpas.length; i++) {
					if (mpas[i] != null) {
						printableList.add(mpas[i]);
					}
				}
				if (printableList.size() > 0) {
					mpas  = new MediaPrintableArea[printableList.size()];
					printableList.toArray(mpas);
				}
				return mpas;
			}

			int match = -1;
			Media media = (Media)attributes.get(Media.class);
			if (media != null && media instanceof MediaSizeName) {
				MediaSizeName msn = (MediaSizeName)media;

				// case when no supported mediasizenames are reported
				// check given media against the default
				if (mediaSizeNames.length == 0 &&
						msn.equals(getDefaultAttributeValue(Media.class))) {
					//default printable area is that of default mediasize
					return mpas;
				}

				for (int i=0; i<mediaSizeNames.length; i++) {
					if (msn.equals(mediaSizeNames[i])) {
						match = i;
					}
				}
			}

			if (match == -1) {
				return null;
			} else {
				MediaPrintableArea []arr = new MediaPrintableArea[1];
				arr[0] = mpas[match];
				return arr;
			}
		} else if (category == NumberUp.class) {
			AttributeClass attribClass = (getAttMap != null) ?
					(AttributeClass)getAttMap.get("number-up-supported") : null;
					if (attribClass != null) {
						int[] values = attribClass.getArrayOfIntValues();
						if (values != null) {
							NumberUp[] nUp = new NumberUp[values.length];
							for (int i=0; i<values.length; i++) {
								nUp[i] = new NumberUp(values[i]);
							}
							return nUp;
						} else {
							return null;
						}
					}
		} else if (category == OrientationRequested.class) {
			if ((flavor != null) &&
					(flavor.equals(DocFlavor.INPUT_STREAM.POSTSCRIPT) ||
							flavor.equals(DocFlavor.URL.POSTSCRIPT) ||
							flavor.equals(DocFlavor.BYTE_ARRAY.POSTSCRIPT))) {
				return null;
			}

			boolean revPort = false;
			OrientationRequested[] orientSup = null;

			AttributeClass attribClass = (getAttMap != null) ?
					(AttributeClass)getAttMap.get("orientation-requested-supported")
					: null;
					if (attribClass != null) {
						int[] orientArray = attribClass.getArrayOfIntValues();
						if ((orientArray != null) && (orientArray.length > 0)) {
							orientSup =
									new OrientationRequested[orientArray.length];
							for (int i=0; i<orientArray.length; i++) {
								switch (orientArray[i]) {
								default:
								case 3 :
									orientSup[i] = OrientationRequested.PORTRAIT;
									break;
								case 4:
									orientSup[i] = OrientationRequested.LANDSCAPE;
									break;
								case 5:
									orientSup[i] =
									OrientationRequested.REVERSE_LANDSCAPE;
									break;
								case 6:
									orientSup[i] =
									OrientationRequested.REVERSE_PORTRAIT;
									revPort = true;
									break;
								}
							}
						}
					}
					if (flavor == null ||
							flavor.equals(DocFlavor.SERVICE_FORMATTED.PAGEABLE) ||
							flavor.equals(DocFlavor.SERVICE_FORMATTED.PRINTABLE)) {

						if (revPort && flavor == null) {
							OrientationRequested []orSup = new OrientationRequested[4];
							orSup[0] = OrientationRequested.PORTRAIT;
							orSup[1] = OrientationRequested.LANDSCAPE;
							orSup[2] = OrientationRequested.REVERSE_LANDSCAPE;
							orSup[3] = OrientationRequested.REVERSE_PORTRAIT;
							return orSup;
						} else {
							OrientationRequested []orSup = new OrientationRequested[3];
							orSup[0] = OrientationRequested.PORTRAIT;
							orSup[1] = OrientationRequested.LANDSCAPE;
							orSup[2] = OrientationRequested.REVERSE_LANDSCAPE;
							return orSup;
						}
					} else {
						return orientSup;
					}
		} else if (category == PageRanges.class) {
			if (flavor == null ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PAGEABLE) ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PRINTABLE)) {
				PageRanges []arr = new PageRanges[1];
				arr[0] = new PageRanges(1, Integer.MAX_VALUE);
				return arr;
			} else {
				// Returning null as this is not yet supported in UnixPrintJob.
				return null;
			}
		} else if (category == RequestingUserName.class) {
			String userName = "";
			try {
				userName = System.getProperty("user.name", "");
			} catch (SecurityException se) {
			}
			return new RequestingUserName(userName, null);
		} else if (category == Sides.class) {
			// The printer takes care of Sides so if short-edge
			// is chosen in a job, the rotation is done by the printer.
			// Orientation is rotated by emulation if pageable
			// or printable so if the document is in Landscape, this may
			// result in double rotation.
			AttributeClass attribClass = (getAttMap != null) ?
					(AttributeClass)getAttMap.get("sides-supported")
					: null;
					if (attribClass != null) {
						String[] sidesArray = attribClass.getArrayOfStringValues();
						if ((sidesArray != null) && (sidesArray.length > 0)) {
							Sides[] sidesSup = new Sides[sidesArray.length];
							for (int i=0; i<sidesArray.length; i++) {
								if (sidesArray[i].endsWith("long-edge")) {
									sidesSup[i] = Sides.TWO_SIDED_LONG_EDGE;
								} else if (sidesArray[i].endsWith("short-edge")) {
									sidesSup[i] = Sides.TWO_SIDED_SHORT_EDGE;
								} else {
									sidesSup[i] = Sides.ONE_SIDED;
								}
							}
							return sidesSup;
						}
					}
		}

		return null;
	}

	//This class is for getting all pre-defined Finishings
	private class ExtFinishing extends Finishings {

		private static final long serialVersionUID = 3212931688024623069L;

		ExtFinishing(int value) {
			super(100); // 100 to avoid any conflicts with predefined values.
		}

		EnumSyntax[] getAll() {
			EnumSyntax[] es = super.getEnumValueTable();
			return es;
		}
	}


	public AttributeSet getUnsupportedAttributes(DocFlavor flavor,
			AttributeSet attributes) {
		if (flavor != null && !isDocFlavorSupported(flavor)) {
			throw new IllegalArgumentException("flavor " + flavor +
					"is not supported");
		}

		if (attributes == null) {
			return null;
		}

		Attribute attr;
		AttributeSet unsupp = new HashAttributeSet();
		Attribute []attrs = attributes.toArray();
		for (int i=0; i<attrs.length; i++) {
			try {
				attr = attrs[i];
				if (!isAttributeCategorySupported(attr.getCategory())) {
					unsupp.add(attr);
				} else if (!isAttributeValueSupported(attr, flavor,
						attributes)) {
					unsupp.add(attr);
				}
			} catch (ClassCastException e) {
			}
		}
		if (unsupp.isEmpty()) {
			return null;
		} else {
			return unsupp;
		}
	}


	public synchronized DocFlavor[] getSupportedDocFlavors() {

		if (supportedDocFlavors != null) {
			int len = supportedDocFlavors.length;
			DocFlavor[] copyflavors = new DocFlavor[len];
			System.arraycopy(supportedDocFlavors, 0, copyflavors, 0, len);
			return copyflavors;
		}
		initAttributes();

		if ((getAttMap != null) &&
				getAttMap.containsKey("document-format-supported")) {

			AttributeClass attribClass =
					(AttributeClass)getAttMap.get("document-format-supported");
			if (attribClass != null) {
				String mimeType;
				boolean psSupported = false;
				String[] docFlavors = attribClass.getArrayOfStringValues();
				DocFlavor[] flavors;
				HashSet<DocFlavor> docList = new HashSet<>();
				int j;
				String hostEnc = DocFlavor.hostEncoding.
						toLowerCase(Locale.ENGLISH);
				boolean addHostEncoding = !hostEnc.equals("utf-8") &&
						!hostEnc.equals("utf-16") && !hostEnc.equals("utf-16be") &&
						!hostEnc.equals("utf-16le") && !hostEnc.equals("us-ascii");

				for (int i = 0; i < docFlavors.length; i++) {
					for (j=0; j<allDocFlavors.length; j++) {
						flavors = (DocFlavor[])allDocFlavors[j];

						mimeType = flavors[0].getMimeType();
						if (mimeType.startsWith(docFlavors[i])) {

							docList.addAll(Arrays.asList(flavors));

							if (mimeType.equals("text/plain") &&
									addHostEncoding) {
								docList.addAll(Arrays.asList(textPlainHost));
							} else if (mimeType.equals("text/html") &&
									addHostEncoding) {
								docList.addAll(Arrays.asList(textHtmlHost));
							} else if (mimeType.equals("image/png")) {
								pngImagesAdded = true;
							} else if (mimeType.equals("image/gif")) {
								gifImagesAdded = true;
							} else if (mimeType.equals("image/jpeg")) {
								jpgImagesAdded = true;
							} else if (mimeType.indexOf("postscript") != -1) {
								psSupported = true;
							}
							break;
						}
					}

					// Not added? Create new DocFlavors
					if (j == allDocFlavors.length) {
						//  make new DocFlavors
						docList.add(new DocFlavor.BYTE_ARRAY(docFlavors[i]));
						docList.add(new DocFlavor.INPUT_STREAM(docFlavors[i]));
						docList.add(new DocFlavor.URL(docFlavors[i]));
					}
				}

				// check if we need to add image DocFlavors
				// and Pageable/Printable flavors
				if (psSupported || isCupsPrinter) {
					/*
                     Always add Pageable and Printable for CUPS
                     since it uses Filters to convert from Postscript
                     to device printer language.
					 */
					docList.add(DocFlavor.SERVICE_FORMATTED.PAGEABLE);
					docList.add(DocFlavor.SERVICE_FORMATTED.PRINTABLE);

					docList.addAll(Arrays.asList(imageJPG));
					docList.addAll(Arrays.asList(imagePNG));
					docList.addAll(Arrays.asList(imageGIF));
				}
				supportedDocFlavors = new DocFlavor[docList.size()];
				docList.toArray(supportedDocFlavors);
				int len = supportedDocFlavors.length;
				DocFlavor[] copyflavors = new DocFlavor[len];
				System.arraycopy(supportedDocFlavors, 0, copyflavors, 0, len);
				return copyflavors;
			}
		}
		return null;
	}


	public boolean isDocFlavorSupported(DocFlavor flavor) {
		if (supportedDocFlavors == null) {
			getSupportedDocFlavors();
		}
		if (supportedDocFlavors != null) {
			for (int f=0; f<supportedDocFlavors.length; f++) {
				if (flavor.equals(supportedDocFlavors[f])) {
					return true;
				}
			}
		}
		return false;
	}


	/**
	 * Finds matching CustomMediaSizeName of given media.
	 */
	public CustomMediaSizeName findCustomMedia(MediaSizeName media) {
		if (customMediaSizeNames == null) {
			return null;
		}
		for (int i=0; i< customMediaSizeNames.length; i++) {
			CustomMediaSizeName custom =
					(CustomMediaSizeName)customMediaSizeNames[i];
			MediaSizeName msn = custom.getStandardMedia();
			if (media.equals(msn)) {
				return customMediaSizeNames[i];
			}
		}
		return null;
	}

	public synchronized Class<?>[] getSupportedAttributeCategories() {
		if (supportedCats != null) {
			Class<?> [] copyCats = new Class<?>[supportedCats.length];
			System.arraycopy(supportedCats, 0, copyCats, 0, copyCats.length);
			return copyCats;
		}

		initAttributes();

		ArrayList<Class<?>> catList = new ArrayList<>();
		Class<?> cl;

		for (int i=0; i < printReqAttribDefault.length; i++) {
			PrintRequestAttribute pra =
					(PrintRequestAttribute)printReqAttribDefault[i];
			if (getAttMap != null &&
					getAttMap.containsKey(pra.getName()+"-supported")) {
				cl = pra.getCategory();
				catList.add(cl);
			}
		}

		// Some IPP printers like lexc710 do not have list of supported media
		// but CUPS can get the media from PPD, so we still report as
		// supported category.
		if (isCupsPrinter) {
			if (!catList.contains(Media.class)) {
				catList.add(Media.class);
			}

			// Always add MediaPrintable for cups,
			// because we can get it from PPD.
			catList.add(MediaPrintableArea.class);

			// this is already supported in UnixPrintJob
			catList.add(Destination.class);

			// It is unfortunate that CUPS doesn't provide a way to query
			// if printer supports collation but since most printers
			// now supports collation and that most OS has a way
			// of setting it, it is a safe assumption to just always
			// include SheetCollate as supported attribute.

			/*
               In Linux, we use Postscript for rendering but Linux still
               has issues in propagating Postscript-embedded setpagedevice
               setting like collation.  Therefore, we temporarily exclude
               Linux.
			 */
			if (!UnixPrintServiceLookup.isLinux()) {
				catList.add(SheetCollate.class);
			}
		}

		// With the assumption that  Chromaticity is equivalent to
		// ColorSupported.
		if (getAttMap != null && getAttMap.containsKey("color-supported")) {
			catList.add(Chromaticity.class);
		}
		supportedCats = new Class[catList.size()];
		catList.toArray(supportedCats);
		Class<?>[] copyCats = new Class<?>[supportedCats.length];
		System.arraycopy(supportedCats, 0, copyCats, 0, copyCats.length);
		return copyCats;
	}


	public boolean isAttributeCategorySupported(Class<? extends Attribute> category)
	{
		if (category == null) {
			throw new NullPointerException("null category");
		}
		if (!(Attribute.class.isAssignableFrom(category))) {
			throw new IllegalArgumentException(category +
					" is not an Attribute");
		}

		if (supportedCats == null) {
			getSupportedAttributeCategories();
		}

		// It is safe to assume that Orientation is always supported
		// and even if CUPS or an IPP device reports it as not,
		// our renderer can do portrait, landscape and
		// reverse landscape.
		if (category == OrientationRequested.class) {
			return true;
		}

		for (int i=0;i<supportedCats.length;i++) {
			if (category == supportedCats[i]) {
				return true;
			}
		}

		return false;
	}

	@Override
	public synchronized PrintServiceAttributeSet updateAttribute() {
		
		return m_attrLookupService.getAttributeDiff(getAttributes());
	}

	@Override
	public synchronized <T extends PrintServiceAttribute>  T getAttribute(Class<T> category) {
			if (category == null) {
				throw new NullPointerException("category");
			}
			initAttributes();
			return m_attrLookupService.getAttribute(category, getAttMap);
	}

	public synchronized PrintServiceAttributeSet getAttributes() {
		// update getAttMap by sending again get-attributes IPP request
		init = false;
		initAttributes();

		HashPrintServiceAttributeSet attrs = new HashPrintServiceAttributeSet();

		for(IPPServiceAttribute attr : serviceAttributes) {
			if(getAttMap != null && getAttMap.containsKey(attr.getName())) {
				PrintServiceAttribute psa;
				if((psa = getAttribute(attr.getClazz())) !=null) {
					attrs.add(psa);
				}
			}
		}
		
		PrintServiceAttributeSet tmpAttr = AttributeSetUtilities.unmodifiableView(attrs);
		m_attrLookupService.updateAttribute(tmpAttr);
		return tmpAttr;
	}

	public boolean isIPPSupportedImages(String mimeType) {
		if (supportedDocFlavors == null) {
			getSupportedDocFlavors();
		}

		if (mimeType.equals("image/png") && pngImagesAdded) {
			return true;
		} else if (mimeType.equals("image/gif") && gifImagesAdded) {
			return true;
		} else if (mimeType.equals("image/jpeg") && jpgImagesAdded) {
			return true;
		}

		return false;
	}


	private boolean isSupportedCopies(Copies copies) {
		CopiesSupported cs = (CopiesSupported)
				getSupportedAttributeValues(Copies.class, null, null);
		int[][] members = cs.getMembers();
		int min, max;
		if ((members.length > 0) && (members[0].length > 0)) {
			min = members[0][0];
			max = members[0][1];
		} else {
			min = 1;
			max = MAXCOPIES;
		}

		int value = copies.getValue();
		return (value >= min && value <= max);
	}

	private boolean isAutoSense(DocFlavor flavor) {
		if (flavor.equals(DocFlavor.BYTE_ARRAY.AUTOSENSE) ||
				flavor.equals(DocFlavor.INPUT_STREAM.AUTOSENSE) ||
				flavor.equals(DocFlavor.URL.AUTOSENSE)) {
			return true;
		}
		else {
			return false;
		}
	}

	/* Return false if flavor is not null, pageable, nor printable and
	 * Destination is part of attributes.
	 */
	private boolean
	isDestinationSupported(DocFlavor flavor, AttributeSet attributes) {

		if ((attributes != null) &&
				(attributes.get(Destination.class) != null) &&
				!(flavor == null ||
				flavor.equals(DocFlavor.SERVICE_FORMATTED.PAGEABLE) ||
				flavor.equals(DocFlavor.SERVICE_FORMATTED.PRINTABLE))) {
			return false;
		}
		return true;
	}


	public boolean isAttributeValueSupported(Attribute attr,
			DocFlavor flavor,
			AttributeSet attributes) {
		if (attr == null) {
			throw new NullPointerException("null attribute");
		}
		if (flavor != null) {
			if (!isDocFlavorSupported(flavor)) {
				throw new IllegalArgumentException(flavor +
						" is an unsupported flavor");
			} else if (isAutoSense(flavor)) {
				return false;
			}
		}
		if (!isAttributeCategorySupported(attr.getCategory())) {
			return false;
		}

		/* Test if the flavor is compatible with the attributes */
		if (!isDestinationSupported(flavor, attributes)) {
			return false;
		}

		/* Test if the flavor is compatible with the category */
		if (attr.getCategory() == Chromaticity.class) {
			if ((flavor == null) ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PAGEABLE) ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PRINTABLE) ||
					!isIPPSupportedImages(flavor.getMimeType())) {
				return attr == Chromaticity.COLOR;
			} else {
				return false;
			}
		} else if (attr.getCategory() == Copies.class) {
			return (flavor == null ||
					!(flavor.equals(DocFlavor.INPUT_STREAM.POSTSCRIPT) ||
							flavor.equals(DocFlavor.URL.POSTSCRIPT) ||
							flavor.equals(DocFlavor.BYTE_ARRAY.POSTSCRIPT))) &&
					isSupportedCopies((Copies)attr);

		} else if (attr.getCategory() == Destination.class) {
			if (flavor == null ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PAGEABLE) ||
					flavor.equals(DocFlavor.SERVICE_FORMATTED.PRINTABLE)) {
				URI uri = ((Destination)attr).getURI();
				if ("file".equals(uri.getScheme()) &&
						!(uri.getSchemeSpecificPart().equals(""))) {
					return true;
				}
			}
			return false;
		} else if (attr.getCategory() == PageRanges.class) {
			if (flavor != null &&
					!(flavor.equals(DocFlavor.SERVICE_FORMATTED.PAGEABLE) ||
							flavor.equals(DocFlavor.SERVICE_FORMATTED.PRINTABLE))) {
				return false;
			}
		} else if (attr.getCategory() == SheetCollate.class) {
			if (flavor != null &&
					!(flavor.equals(DocFlavor.SERVICE_FORMATTED.PAGEABLE) ||
							flavor.equals(DocFlavor.SERVICE_FORMATTED.PRINTABLE))) {
				return false;
			}
		} else if (attr.getCategory() == Sides.class) {
			Sides[] sidesArray = (Sides[])getSupportedAttributeValues(
					Sides.class,
					flavor,
					attributes);

			if (sidesArray != null) {
				for (int i=0; i<sidesArray.length; i++) {
					if (sidesArray[i] == (Sides)attr) {
						return true;
					}
				}
			}
			return false;
		} else if (attr.getCategory() == OrientationRequested.class) {
			OrientationRequested[] orientArray =
					(OrientationRequested[])getSupportedAttributeValues(
							OrientationRequested.class,
							flavor,
							attributes);

			if (orientArray != null) {
				for (int i=0; i<orientArray.length; i++) {
					if (orientArray[i] == (OrientationRequested)attr) {
						return true;
					}
				}
			}
			return false;
		}
		return true;
	}


	public synchronized Object
	getDefaultAttributeValue(Class<? extends Attribute> category)
	{
		if (category == null) {
			throw new NullPointerException("null category");
		}
		if (!Attribute.class.isAssignableFrom(category)) {
			throw new IllegalArgumentException(category +
					" is not an Attribute");
		}
		if (!isAttributeCategorySupported(category)) {
			return null;
		}

		initAttributes();

		String catName = null;
		for (int i=0; i < printReqAttribDefault.length; i++) {
			PrintRequestAttribute pra =
					(PrintRequestAttribute)printReqAttribDefault[i];
			if (pra.getCategory() == category) {
				catName = pra.getName();
				break;
			}
		}
		String attribName = catName+"-default";
		AttributeClass attribClass = (getAttMap != null) ?
				(AttributeClass)getAttMap.get(attribName) : null;

				if (category == Copies.class) {
					if (attribClass != null) {
						return new Copies(attribClass.getIntValue());
					} else {
						return new Copies(1);
					}
				} else if (category == Chromaticity.class) {
					return Chromaticity.COLOR;
				} else if (category == Destination.class) {
					try {
						return new Destination((new File("out.ps")).toURI());
					} catch (SecurityException se) {
						try {
							return new Destination(new URI("file:out.ps"));
						} catch (URISyntaxException e) {
							return null;
						}
					}
				} else if (category == Fidelity.class) {
					return Fidelity.FIDELITY_FALSE;
				} else if (category == Finishings.class) {
					return Finishings.NONE;
				} else if (category == JobName.class) {
					return new JobName("Java Printing", null);
				} else if (category == JobSheets.class) {
					if (attribClass != null &&
							attribClass.getStringValue().equals("none")) {
						return JobSheets.NONE;
					} else {
						return JobSheets.STANDARD;
					}
				} else if (category == Media.class) {
					if (defaultMediaIndex == -1) {
						defaultMediaIndex = 0;
					}
					if (mediaSizeNames.length == 0) {
						String defaultCountry = Locale.getDefault().getCountry();
						if (defaultCountry != null &&
								(defaultCountry.equals("") ||
										defaultCountry.equals(Locale.US.getCountry()) ||
										defaultCountry.equals(Locale.CANADA.getCountry()))) {
							return MediaSizeName.NA_LETTER;
						} else {
							return MediaSizeName.ISO_A4;
						}
					}

					if (attribClass != null) {
						String name = attribClass.getStringValue();
						if (isCupsPrinter) {
							return mediaSizeNames[defaultMediaIndex];
						} else {
							for (int i=0; i< mediaSizeNames.length; i++) {
								if (mediaSizeNames[i].toString().indexOf(name) != -1) {
									defaultMediaIndex = i;
									return mediaSizeNames[defaultMediaIndex];
								}
							}
						}
					}
					return mediaSizeNames[defaultMediaIndex];

				} else if (category == MediaPrintableArea.class) {
					MediaPrintableArea[] mpas;
					if ((cps != null)  &&
							((mpas = cps.getMediaPrintableArea()) != null)) {
						if (defaultMediaIndex == -1) {
							// initializes value of defaultMediaIndex
							getDefaultAttributeValue(Media.class);
						}
						return mpas[defaultMediaIndex];
					} else {
						String defaultCountry = Locale.getDefault().getCountry();
						float iw, ih;
						if (defaultCountry != null &&
								(defaultCountry.equals("") ||
										defaultCountry.equals(Locale.US.getCountry()) ||
										defaultCountry.equals(Locale.CANADA.getCountry()))) {
							iw = MediaSize.NA.LETTER.getX(Size2DSyntax.INCH) - 0.5f;
							ih = MediaSize.NA.LETTER.getY(Size2DSyntax.INCH) - 0.5f;
						} else {
							iw = MediaSize.ISO.A4.getX(Size2DSyntax.INCH) - 0.5f;
							ih = MediaSize.ISO.A4.getY(Size2DSyntax.INCH) - 0.5f;
						}
						return new MediaPrintableArea(0.25f, 0.25f, iw, ih,
								MediaPrintableArea.INCH);
					}
				} else if (category == NumberUp.class) {
					return new NumberUp(1); // for CUPS this is always 1
				} else if (category == OrientationRequested.class) {
					if (attribClass != null) {
						switch (attribClass.getIntValue()) {
						default:
						case 3: return OrientationRequested.PORTRAIT;
						case 4: return OrientationRequested.LANDSCAPE;
						case 5: return OrientationRequested.REVERSE_LANDSCAPE;
						case 6: return OrientationRequested.REVERSE_PORTRAIT;
						}
					} else {
						return OrientationRequested.PORTRAIT;
					}
				} else if (category == PageRanges.class) {
					if (attribClass != null) {
						int[] range = attribClass.getIntRangeValue();
						return new PageRanges(range[0], range[1]);
					} else {
						return new PageRanges(1, Integer.MAX_VALUE);
					}
				} else if (category == RequestingUserName.class) {
					String userName = "";
					try {
						userName = System.getProperty("user.name", "");
					} catch (SecurityException se) {
					}
					return new RequestingUserName(userName, null);
				} else if (category == SheetCollate.class) {
					return SheetCollate.UNCOLLATED;
				} else if (category == Sides.class) {
					if (attribClass != null) {
						if (attribClass.getStringValue().endsWith("long-edge")) {
							return Sides.TWO_SIDED_LONG_EDGE;
						} else if (attribClass.getStringValue().endsWith(
								"short-edge")) {
							return Sides.TWO_SIDED_SHORT_EDGE;
						}
					}
					return Sides.ONE_SIDED;
				}

				return null;
	}

	public void wakeNotifier() {
		synchronized (this) {
			if (notifier != null) {
				notifier.wake();
			}
		}
	}

	public void addPrintServiceAttributeListener(
			PrintServiceAttributeListener listener) {
		synchronized (this) {
			if (listener == null) {
				return;
			}
			if (notifier == null) {
				notifier = new ServiceNotifier(this);
			}
			notifier.addListener(listener);
		}
	}

	public void removePrintServiceAttributeListener(
			PrintServiceAttributeListener listener) {
		synchronized (this) {
			if (listener == null || notifier == null ) {
				return;
			}
			notifier.removeListener(listener);
			if (notifier.isEmpty()) {
				notifier.stopNotifier();
				notifier = null;
			}
		}
	}

	String getDest() {
		return printer;
	}

	public String getName() {
		/*
		 * Mac is using printer-info IPP attribute for its human-readable printer
		 * name and is also the identifier used in NSPrintInfo:setPrinter.
		 */
		if (UnixPrintServiceLookup.isMac()) {
			PrintServiceAttributeSet psaSet = this.getAttributes();
			if (psaSet != null) {
				PrinterInfo pName = (PrinterInfo)psaSet.get(PrinterInfo.class);
				if (pName != null) {
					return pName.toString();
				}
			}
		}
		return printer;
	}

	public static HttpURLConnection getIPPConnection(URL url) {
		HttpURLConnection connection;
		URLConnection urlc;
		try {
			urlc = url.openConnection();
		} catch (java.io.IOException ioe) {
			return null;
		}
		if (!(urlc instanceof HttpURLConnection)) {
			return null;
		}
		connection = (HttpURLConnection)urlc;
		connection.setUseCaches(false);
		connection.setDefaultUseCaches(false);
		connection.setDoInput(true);
		connection.setDoOutput(true);
		connection.setRequestProperty("Content-type", "application/ipp");
		return connection;
	}


	public synchronized boolean isPostscript() {
		if (isPS == null) {
			isPS = Boolean.TRUE;
			if (isCupsPrinter) {
				try {
					urlConnection = getIPPConnection(
							new URL(myURL+".ppd"));

					InputStream is = urlConnection.getInputStream();
					if (is != null) {
						BufferedReader d =
								new BufferedReader(new InputStreamReader(is,
										Charset.forName("ISO-8859-1")));
						String lineStr;
						while ((lineStr = d.readLine()) != null) {
							if (lineStr.startsWith("*cupsFilter:")) {
								isPS = Boolean.FALSE;
								break;
							}
						}
					}
				} catch (java.io.IOException e) {
					debug_println(" isPostscript, e= "+e);
					/* if PPD is not found, this may be a raw printer
                       and in this case it is assumed that it is a
                       Postscript printer */
					// do nothing
				}
			}
		}
		return isPS.booleanValue();
	}


	private void opGetAttributes() {
		try {
			debug_println(debugPrefix+"opGetAttributes myURI "+myURI+" myURL "+myURL);

			AttributeClass attClNoUri[] = {
					AttributeClass.ATTRIBUTES_CHARSET,
					AttributeClass.ATTRIBUTES_NATURAL_LANGUAGE};

			AttributeClass attCl[] = {
					AttributeClass.ATTRIBUTES_CHARSET,
					AttributeClass.ATTRIBUTES_NATURAL_LANGUAGE,
					new AttributeClass("printer-uri",
							AttributeClass.TAG_URI,
							""+myURI)};

			OutputStream os = urlConnection.getOutputStream();
			if (os == null) {
				return;
			}

			boolean success = (myURI == null) ?
					writeIPPRequest(os, OP_GET_ATTRIBUTES, attClNoUri) :
						writeIPPRequest(os, OP_GET_ATTRIBUTES, attCl);
					if (success) {
						InputStream is = null;
						if ((is = urlConnection.getInputStream())!=null) {
							HashMap<String, AttributeClass>[] responseMap = readIPPResponse(is);

							if (responseMap != null && responseMap.length > 0) {
								getAttMap = responseMap[0];
							}
						} else {
							debug_println(debugPrefix+"opGetAttributes - null input stream");
						}
						is.close();
					}
					os.close();
		} catch (java.io.IOException e) {
			debug_println(debugPrefix+"opGetAttributes - input/output stream: "+e);
		}
	}


	public static boolean writeIPPRequest(OutputStream os,
			String operCode,
			AttributeClass[] attCl) {
		OutputStreamWriter osw;
		try {
			osw = new OutputStreamWriter(os, "UTF-8");
		} catch (java.io.UnsupportedEncodingException exc) {
			debug_println(debugPrefix+"writeIPPRequest, UTF-8 not supported? Exception: "+exc);
			return false;
		}
		debug_println(debugPrefix+"writeIPPRequest, op code= "+operCode);
		char[] opCode =  new char[2];
		opCode[0] =  (char)Byte.parseByte(operCode.substring(0,2), 16);
		opCode[1] =  (char)Byte.parseByte(operCode.substring(2,4), 16);
		char[] bytes = {0x01, 0x01, 0x00, 0x01};
		try {
			osw.write(bytes, 0, 2); // version number
			osw.write(opCode, 0, 2); // operation code
			bytes[0] = 0x00; bytes[1] = 0x00;
			osw.write(bytes, 0, 4); // request ID #1

			bytes[0] = 0x01; // operation-group-tag
			osw.write(bytes[0]);

			String valStr;
			char[] lenStr;

			AttributeClass ac;
			for (int i=0; i < attCl.length; i++) {
				ac = attCl[i];
				osw.write(ac.getType()); // value tag

				lenStr = ac.getLenChars();
				osw.write(lenStr, 0, 2); // length
				osw.write(""+ac, 0, ac.getName().length());

				// check if string range (0x35 -> 0x49)
				if (ac.getType() >= AttributeClass.TAG_TEXT_LANGUAGE &&
						ac.getType() <= AttributeClass.TAG_MIME_MEDIATYPE){
					valStr = (String)ac.getObjectValue();
					bytes[0] = 0; bytes[1] = (char)valStr.length();
					osw.write(bytes, 0, 2);
					osw.write(valStr, 0, valStr.length());
				} // REMIND: need to support other value tags but for CUPS
				// string is all we need.
			}

			osw.write(GRPTAG_END_ATTRIBUTES);
			osw.flush();
			osw.close();
		} catch (java.io.IOException ioe) {
			debug_println(debugPrefix+"writeIPPRequest, IPPPrintService Exception in writeIPPRequest: "+ioe);
			return false;
		}
		return true;
	}


	@SuppressWarnings("unchecked")
	public static HashMap<String, AttributeClass>[] readIPPResponse(InputStream inputStream) {

		if (inputStream == null) {
			return null;
		}

		byte response[] = new byte[MAX_ATTRIBUTE_LENGTH];
		try {

			DataInputStream ois = new DataInputStream(inputStream);

			// read status and ID
			if ((ois.read(response, 0, 8) > -1) &&
					(response[2] == STATUSCODE_SUCCESS)) {

				ByteArrayOutputStream outObj;
				int counter=0;
				short len = 0;
				String attribStr = null;
				// assign default value
				byte valTagByte = AttributeClass.TAG_KEYWORD;
				ArrayList<HashMap<String, AttributeClass>> respList = new ArrayList<>();
				HashMap<String, AttributeClass> responseMap = new HashMap<>();

				response[0] = ois.readByte();

				// check for group tags
				while ((response[0] >= GRPTAG_OP_ATTRIBUTES) &&
						(response[0] <= GRPTAG_PRINTER_ATTRIBUTES)
						&& (response[0] != GRPTAG_END_ATTRIBUTES)) {
					debug_println(debugPrefix+"readIPPResponse, checking group tag,  response[0]= "+
							response[0]);

					outObj = new ByteArrayOutputStream();
					//make sure counter and attribStr are re-initialized
					counter = 0;
					attribStr = null;

					// read value tag
					response[0] = ois.readByte();
					while (response[0] >= AttributeClass.TAG_UNSUPPORTED_VALUE &&
							response[0] <= AttributeClass.TAG_MEMBER_ATTRNAME) {
						// read name length
						len  = ois.readShort();

						// If current value is not part of previous attribute
						// then close stream and add it to HashMap.
						// It is part of previous attribute if name length=0.
						if ((len != 0) && (attribStr != null)) {
							//last byte is the total # of values
							outObj.write(counter);
							outObj.flush();
							outObj.close();
							byte outArray[] = outObj.toByteArray();

							// if key exists, new HashMap
							if (responseMap.containsKey(attribStr)) {
								respList.add(responseMap);
								responseMap = new HashMap<String, AttributeClass>();
							}

							// exclude those that are unknown
							if (valTagByte >= AttributeClass.TAG_INT) {
								AttributeClass ac =
										new AttributeClass(attribStr,
												valTagByte,
												outArray);

								responseMap.put(ac.getName(), ac);
								debug_println(debugPrefix+ "readIPPResponse "+ac);
							}

							outObj = new ByteArrayOutputStream();
							counter = 0; //reset counter
						}
						//check if this is new value tag
						if (counter == 0) {
							valTagByte = response[0];
						}
						// read attribute name
						if (len != 0) {
							// read "len" characters
							// make sure it doesn't exceed the maximum
							if (len > MAX_ATTRIBUTE_LENGTH) {
								response = new byte[len]; // expand as needed
							}
							ois.read(response, 0, len);
							attribStr = new String(response, 0, len);
						}
						// read value length
						len  = ois.readShort();
						// write name length
						outObj.write(len);
						// read value, make sure it doesn't exceed the maximum
						if (len > MAX_ATTRIBUTE_LENGTH) {
							response = new byte[len]; // expand as needed
						}
						ois.read(response, 0, len);
						// write value of "len" length
						outObj.write(response, 0, len);
						counter++;
						// read next byte
						response[0] = ois.readByte();
					}

					if (attribStr != null) {
						outObj.write(counter);
						outObj.flush();
						outObj.close();

						// if key exists in old HashMap, new HashMap
						if ((counter != 0) &&
								responseMap.containsKey(attribStr)) {
							respList.add(responseMap);
							responseMap = new HashMap<String, AttributeClass>();
						}

						byte outArray[] = outObj.toByteArray();

						AttributeClass ac =
								new AttributeClass(attribStr,
										valTagByte,
										outArray);
						responseMap.put(ac.getName(), ac);
					}
				}
				ois.close();
				if ((responseMap != null) && (responseMap.size() > 0)) {
					respList.add(responseMap);
				}
				return respList.toArray(
						new HashMap[respList.size()]);
			} else {
				debug_println(debugPrefix+
						"readIPPResponse client error, IPP status code: 0x"+
						toHex(response[2]) + toHex(response[3]));
				return null;
			}

		} catch (java.io.IOException e) {
			debug_println(debugPrefix+"readIPPResponse: "+e);
			if (debugPrint) {
				e.printStackTrace();
			}
			return null;
		}
	}

	private static String toHex(byte v) {
		String s = Integer.toHexString(v&0xff);
		return (s.length() == 2) ? s :  "0"+s;
	}

	public String toString() {
		return "IPP Printer : " + getName();
	}

	public boolean equals(Object obj) {
		return  (obj == this ||
				(obj instanceof IPPPrintService &&
						((IPPPrintService)obj).getName().equals(getName())));
	}

	public int hashCode() {
		return this.getClass().hashCode()+getName().hashCode();
	}

	@Override
	public ServiceUIFactory getServiceUIFactory() {
		return null;
	}
}