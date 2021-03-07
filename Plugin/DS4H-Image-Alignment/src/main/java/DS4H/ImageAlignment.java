package DS4H;

import DS4H.BufferedImage.BufferedImage;
import DS4H.MainDialog.MainDialog;
import DS4H.AlignDialog.AlignDialog;
import DS4H.AlignDialog.OnAlignDialogEventListener;
import DS4H.AlignDialog.event.IAlignDialogEvent;
import DS4H.AlignDialog.event.ReuseImageEvent;
import DS4H.AlignDialog.event.SaveEvent;
import DS4H.PreviewDialog.OnPreviewDialogEventListener;
import DS4H.PreviewDialog.PreviewDialog;
import DS4H.MainDialog.OnMainDialogEventListener;
import DS4H.MainDialog.event.*;
import DS4H.PreviewDialog.event.CloseDialogEvent;
import DS4H.PreviewDialog.event.IPreviewDialogEvent;
import DS4H.RemoveDialog.OnRemoveDialogEventListener;
import DS4H.RemoveDialog.RemoveImageDialog;
import DS4H.RemoveDialog.event.IRemoveDialogEvent;
import ij.*;
import ij.gui.*;

import ij.io.FileSaver;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.plugin.frame.RoiManager;
import ij.process.ColorProcessor;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import io.scif.services.DatasetIOService;
import loci.formats.UnknownFormatException;
import net.imagej.Dataset;
import org.scijava.AbstractContextual;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.plugin.Plugin;

import net.imagej.ImageJ;

import javax.swing.*;
import java.awt.*;
import java.awt.image.ColorModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Plugin(type = Command.class, headless = true,
		menuPath = "Plugins>Registration>DSH4 Image Alignment")
public class ImageAlignment extends AbstractContextual implements Command, OnMainDialogEventListener, OnPreviewDialogEventListener, OnAlignDialogEventListener, OnRemoveDialogEventListener {
	private ImagesManager manager;
	private BufferedImage image = null;
	private MainDialog mainDialog;
	private PreviewDialog previewDialog;
	private AlignDialog alignDialog;
	private LoadingDialog loadingDialog;
	private AboutDialog aboutDialog;
	private RemoveImageDialog removeImageDialog;

	private List<String> tempImages = new ArrayList<>();
	private boolean alignedImageSaved = false;

	static private String IMAGES_SCALED_MESSAGE = "Image size too large: image has been scaled for compatibility.";
	static private String SINGLE_IMAGE_MESSAGE = "Only one image detected in the stack: align operation will be unavailable.";
	static private String IMAGES_OVERSIZE_MESSAGE = "Cannot open the selected image: image exceed supported dimensions.";
	static private String ALIGNED_IMAGE_NOT_SAVED_MESSAGE = "Aligned images not saved: are you sure you want to exit without saving?";
	static private String DELETE_ALL_IMAGES = "Do you confirm to delete all the images of the stack?";
	static private String IMAGE_SAVED_MESSAGE  = "Image successfully saved";
	static private String ROI_NOT_ADDED_MESSAGE = "One or more corner points not added: they exceed the image bounds";
	static private String INSUFFICIENT_MEMORY_MESSAGE = "Insufficient computer memory (RAM) available. \n\n\t Try to increase the allocated memory by going to \n\n\t                Edit  ▶ Options  ▶ Memory & Threads \n\n\t Change \"Maximum Memory\" to, at most, 1000 MB less than your computer's total RAM.";
	static private String UNKNOWN_FORMAT_MESSAGE = "Error: trying to open a file with a unsupported format.";
	static private String IMAGE_SIZE_TOO_BIG = "During computation the expected file size overcame imagej file limit. To continue, deselect \"keep all pixel data\" option.";
	static private long TotalMemory = 0;
	public static void main(final String... args) {
		ImageJ ij = new ImageJ();
		ij.launch(args);
		ImageAlignment plugin = new ImageAlignment();
		plugin.setContext(ij.getContext());
		plugin.run();
	}

	@Override
	public void run() {
		// Chiediamo come prima cosa il file all'utente
		String pathFile = promptForFile();
		if (pathFile.equals("nullnull"))
			return;
		this.initialize(pathFile);

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			this.tempImages.forEach(imagePath -> {
				try {
					Files.deleteIfExists(Paths.get(imagePath));
				} catch (IOException e) { }
			});
		}));
	}

	@Override
	public Thread onMainDialogEvent(IMainDialogEvent dialogEvent) {
		WindowManager.setCurrentWindow(image.getWindow());
		if(dialogEvent instanceof PreviewImageEvent) {
			new Thread(() -> {
				PreviewImageEvent event = (PreviewImageEvent)dialogEvent;
				if(!event.getValue()) {
					previewDialog.close();
					return;
				}

				try {
					this.loadingDialog.showDialog();
					previewDialog = new PreviewDialog(manager.get(manager.getCurrentIndex()), this, manager.getCurrentIndex(), manager.getNImages(), "Preview Image " + (manager.getCurrentIndex()+1) + "/" + manager.getNImages());
				} catch (Exception e) { }
				this.loadingDialog.hideDialog();
				previewDialog.pack();
				previewDialog.setVisible(true);
				previewDialog.drawRois();
			}).start();
		}

		if(dialogEvent instanceof ChangeImageEvent) {
			image.removeMouseListeners();
			Thread t = new Thread(() -> {
				ChangeImageEvent event = (ChangeImageEvent)dialogEvent;
				if((event.getChangeDirection() == ChangeImageEvent.ChangeDirection.NEXT && !manager.hasNext()) ||
						event.getChangeDirection() == ChangeImageEvent.ChangeDirection.PREV && !manager.hasPrevious()){
					this.loadingDialog.hideDialog();
					return;
				}

				// per evitare memory leaks, invochiamo manualmente il garbage collector ad ogni cambio di immagine
				image = event.getChangeDirection() == ChangeImageEvent.ChangeDirection.NEXT ? this.manager.next() : this.manager.previous();
				mainDialog.changeImage(image);
				mainDialog.setPrevImageButtonEnabled(manager.hasPrevious());
				mainDialog.setNextImageButtonEnabled(manager.hasNext());
				mainDialog.setTitle(MessageFormat.format("Editor Image {0}/{1}", manager.getCurrentIndex() + 1, manager.getNImages()));
				image.buildMouseListener();
				this.loadingDialog.hideDialog();
				refreshRoiGUI();
				System.gc();
			});
			t.start();
			this.loadingDialog.showDialog();
			return t;
		}

		if(dialogEvent instanceof DeleteRoiEvent){
			DeleteRoiEvent event = (DeleteRoiEvent)dialogEvent;
			image.getManager().select(event.getRoiIndex());
			image.getManager().runCommand("Delete");

			refreshRoiGUI();
		}

		if(dialogEvent instanceof AddRoiEvent) {
			AddRoiEvent event = (AddRoiEvent)dialogEvent;

			Prefs.useNamesAsLabels = true;
			Prefs.noPointLabels = false;
			int roiWidth = Toolkit.getDefaultToolkit().getScreenSize().width > image.getWidth() ? Toolkit.getDefaultToolkit().getScreenSize().width : image.getWidth() ;
			roiWidth = (int)(roiWidth * 0.03);
			OvalRoi outer = new OvalRoi (event.getClickCoords().x - (roiWidth / 2), event.getClickCoords().y - (roiWidth/2), roiWidth, roiWidth);

			// get roughly the 0,25% of the width of the image as stroke width of th rois added.
			// If the resultant value is too small, set it as the minimum value
			int strokeWidth = (int) (image.getWidth() * 0.0025) > 3 ? (int) (image.getWidth() * 0.0025) : 3;
			outer.setStrokeWidth(strokeWidth);

		 	outer.setImage(image);

		 	outer.setStrokeColor(Color.BLUE);
			Overlay over = new Overlay();
			over.drawBackgrounds(false);
			over.drawLabels(false);
			over.drawNames(true);
			over.setLabelFontSize(Math.round(strokeWidth * 1f), "scale");
			over.setLabelColor(Color.BLUE);
			over.setStrokeWidth((double)strokeWidth);
			over.setStrokeColor(Color.BLUE);
			outer.setName("•");

			Arrays.stream(image.getManager().getRoisAsArray()).forEach(roi -> over.add(roi));
			over.add(outer);
			image.getManager().setOverlay(over);
			refreshRoiGUI();
			refreshRoiGUI();
		}

		if(dialogEvent instanceof SelectedRoiEvent) {
			SelectedRoiEvent event = (SelectedRoiEvent)dialogEvent;
			Arrays.stream(image.getManager().getSelectedRoisAsArray()).forEach(roi -> roi.setStrokeColor(Color.BLUE));
			image.getManager().select(event.getRoiIndex());

			image.getRoi().setStrokeColor(Color.yellow);
			image.updateAndDraw();
			if(previewDialog != null && previewDialog.isVisible())
				previewDialog.drawRois();
		}

		if(dialogEvent instanceof SelectedRoiFromOvalEvent) {
			SelectedRoiFromOvalEvent event = (SelectedRoiFromOvalEvent)dialogEvent;
			mainDialog.lst_rois.setSelectedIndex(event.getRoiIndex());
		}

		if(dialogEvent instanceof DeselectedRoiEvent) {
			DeselectedRoiEvent event = (DeselectedRoiEvent)dialogEvent;
			Arrays.stream(image.getManager().getSelectedRoisAsArray()).forEach(roi -> roi.setStrokeColor(Color.BLUE));
			image.getManager().select(event.getRoiIndex());
			previewDialog.drawRois();
		}

		if(dialogEvent instanceof AlignEvent) {
			AlignEvent event = (AlignEvent)dialogEvent;
			this.loadingDialog.showDialog();

			// Timeout is necessary to ensure that the loadingDialog is shown
			Utilities.setTimeout(() -> {
				try {
					VirtualStack virtualStack;
					ImagePlus transformedImagesStack;
					if(event.isKeepOriginal()) {
						// MAX IMAGE SIZE SEARCH AND SOURCE IMG SELECTION
						// search for the maximum size of the images and the index of the image with the maximum width
						List<Dimension> dimensions = manager.getImagesDimensions();
						int sourceImgIndex = -1;
						Dimension maximumSize = new Dimension();
						for(int i =0 ; i < dimensions.size() ; i++) {

							Dimension dimension = dimensions.get(i);
							if(dimension.width > maximumSize.width) {
								maximumSize.width = dimension.width;
								sourceImgIndex = i;
							}
							if(dimension.height > maximumSize.height)
								maximumSize.height = dimension.height;
						}
						BufferedImage sourceImg = manager.get(sourceImgIndex, true);

						// FINAL STACK SIZE CALCULATION AND OFFSETS
						Dimension finalStackDimension = new Dimension(maximumSize.width, maximumSize.height);
						List<Integer> offsetsX = new ArrayList<>();
						List<Integer> offsetsY = new ArrayList<>();
						List<RoiManager> managers = manager.getRoiManagers();
						for(int i=0; i < managers.size(); i++) {
							if(i == sourceImgIndex){
								offsetsX.add(0);
								offsetsY.add(0);
								continue;
							}
							Roi roi = managers.get(i).getRoisAsArray()[0];
							offsetsX.add((int)(roi.getXBase() - sourceImg.getManager().getRoisAsArray()[0].getXBase()));
							offsetsY.add((int)(roi.getYBase() - sourceImg.getManager().getRoisAsArray()[0].getYBase()));
						}
						int maxOffsetX = (offsetsX.stream().max(Comparator.naturalOrder()).get());
						int maxOffsetXIndex = offsetsX.indexOf(maxOffsetX);
						if(maxOffsetX <= 0) {
							maxOffsetX = 0;
							maxOffsetXIndex = -1;
						}

						int maxOffsetY = (offsetsY.stream().max(Comparator.naturalOrder()).get());
						int maxOffsetYIndex = offsetsY.indexOf(maxOffsetY);
						if(maxOffsetY <= 0) {
							maxOffsetY = 0;
						}
						// Calculate the final stack size. It is calculated as maximumImageSize + maximum offset in respect of the source image
						finalStackDimension.width = finalStackDimension.width + maxOffsetX;
						finalStackDimension.height += sourceImg.getHeight() == maximumSize.height ? maxOffsetY : 0;

						// The final stack of the image is exceeding the maximum size of the images for imagej (see http://imagej.1557.x6.nabble.com/Large-image-td5015380.html)
						if (((double)finalStackDimension.width * finalStackDimension.height) > Integer.MAX_VALUE){
							JOptionPane.showMessageDialog(null, IMAGE_SIZE_TOO_BIG, "Error: image size too big", JOptionPane.ERROR_MESSAGE);
							loadingDialog.hideDialog();
							return;
						}

						ImageProcessor processor = sourceImg.getProcessor().createProcessor(finalStackDimension.width, finalStackDimension.height);
						processor.insert(sourceImg.getProcessor(), maxOffsetX, maxOffsetY);

						virtualStack = new VirtualStack(finalStackDimension.width, finalStackDimension.height, ColorModel.getRGBdefault(), IJ.getDir("temp"));
						addToVirtualStack(new ImagePlus("", processor), virtualStack);

						for(int i=0; i < manager.getNImages() ; i++) {
							if(i == sourceImgIndex)
								continue;
							ImageProcessor newProcessor = new ColorProcessor(finalStackDimension.width, maximumSize.height);
							ImagePlus transformedImage = LeastSquareImageTransformation.transform(manager.get(i, true), sourceImg, event.isRotate());

							BufferedImage transformedOriginalImage = manager.get(i, true);
							final int[] edgeX = {-1};
							final int[] edgeY = {-1};
							Arrays.stream(transformedOriginalImage.getManager().getRoisAsArray()).forEach(roi -> {
								if(edgeX[0] == -1 || edgeX[0] > roi.getXBase())
									edgeX[0] = (int) roi.getXBase();
								if(edgeY[0] == -1 || edgeY[0] > roi.getYBase())
									edgeY[0] = (int) roi.getYBase();
							});

							final int[] edgeX2 = {-1};
							final int[] edgeY2 = {-1};
							Arrays.stream(sourceImg.getManager().getRoisAsArray()).forEach(roi -> {
								if(edgeX2[0] == -1 || edgeX2[0] > roi.getXBase())
									edgeX2[0] = (int) roi.getXBase();
								if(edgeY2[0] == -1 || edgeY2[0] > roi.getYBase())
									edgeY2[0] = (int) roi.getYBase();
							});

							int offsetXOriginal = 0;
							if(offsetsX.get(i) < 0)
								offsetXOriginal = Math.abs(offsetsX.get(i));
							offsetXOriginal += maxOffsetXIndex != i ? maxOffsetX : 0;

							int offsetXTransformed = 0;
							if(offsetsX.get(i) > 0 && maxOffsetXIndex != i)
								offsetXTransformed = Math.abs(offsetsX.get(i));
							offsetXTransformed += maxOffsetX;

							int difference = (int)(managers.get(maxOffsetYIndex).getRoisAsArray()[0].getYBase() - managers.get(i).getRoisAsArray()[0].getYBase());
							newProcessor.insert(transformedOriginalImage.getProcessor(), offsetXOriginal, difference);
							newProcessor.insert(transformedImage.getProcessor(), offsetXTransformed, (maxOffsetY));
							addToVirtualStack(new ImagePlus("", newProcessor), virtualStack);
						}
					}
					else {
						BufferedImage sourceImg = manager.get(0, true);
						virtualStack = new VirtualStack(sourceImg.getWidth(), sourceImg.getHeight(), ColorModel.getRGBdefault(), IJ.getDir("temp"));
						addToVirtualStack(sourceImg, virtualStack);
						for(int i=1; i < manager.getNImages(); i++) {
							System.gc();
							ImagePlus img = LeastSquareImageTransformation.transform(manager.get(i, true), sourceImg, event.isRotate());
							addToVirtualStack(img, virtualStack);
						}
					}
					transformedImagesStack = new ImagePlus("", virtualStack);
					String filePath = IJ.getDir("temp") + transformedImagesStack.hashCode() + ".tiff";

					new ImageConverter(transformedImagesStack).convertToRGB();
					new FileSaver(transformedImagesStack).saveAsTiff(filePath);
					System.gc();
					tempImages.add(filePath);
					this.loadingDialog.hideDialog();
					alignDialog = new AlignDialog(transformedImagesStack, this);
					alignDialog.pack();
					alignDialog.setVisible(true);
				}
				catch (Exception e) {
					e.printStackTrace();
				}
				this.loadingDialog.hideDialog();
			}, 10);
		}

		if(dialogEvent instanceof OpenFileEvent || dialogEvent instanceof ExitEvent) {
			boolean roisPresent = manager.getRoiManagers().stream().filter(manager -> manager.getRoisAsArray().length != 0).count() > 0;
			if(roisPresent){
				String[] buttons = { "Yes", "No"};
				String message = dialogEvent instanceof OpenFileEvent ? "This will replace the existing image. Proceed anyway?" : "You will lose the existing added landmarks. Proceed anyway?";
				int answer = JOptionPane.showOptionDialog(null, message, "Careful now",
						JOptionPane.WARNING_MESSAGE, 0, null, buttons, buttons[1]);

				if(answer == 1)
					return null;
			}

			if(dialogEvent instanceof OpenFileEvent) {
				String pathFile = promptForFile();
				if (pathFile.equals("nullnull"))
					return null;
				this.disposeAll();
				this.initialize(pathFile);
			}
			else {
				disposeAll();
				System.exit(0);
			}
		}

		if(dialogEvent instanceof OpenAboutEvent) {
			this.aboutDialog.setVisible(true);
		}

		if(dialogEvent instanceof MovedRoiEvent) {
			this.mainDialog.refreshROIList(image.getManager());
			if(previewDialog != null)
				this.previewDialog.drawRois();
		}

		if(dialogEvent instanceof AddFileEvent) {
			String pathFile = ((AddFileEvent) dialogEvent).getFilePath();
			try {
				long memory = ImageFile.estimateMemoryUsage(pathFile);
				TotalMemory += memory;
				if(TotalMemory >= Runtime.getRuntime().maxMemory()) {
					JOptionPane.showMessageDialog(null, INSUFFICIENT_MEMORY_MESSAGE, "Error: insufficient memory", JOptionPane.ERROR_MESSAGE);
					return null;
				}
				manager.addFile(pathFile);
			}
			catch(UnknownFormatException e){
				loadingDialog.hideDialog();
				JOptionPane.showMessageDialog(null, UNKNOWN_FORMAT_MESSAGE, "Error: unknow format", JOptionPane.ERROR_MESSAGE);
			}
			catch (Exception e) {
				e.printStackTrace();
			}
			mainDialog.setPrevImageButtonEnabled(manager.hasPrevious());
			mainDialog.setNextImageButtonEnabled(manager.hasNext());
			mainDialog.setTitle(MessageFormat.format("Editor Image {0}/{1}", manager.getCurrentIndex() + 1, manager.getNImages()));
			refreshRoiGUI();
		}

		if(dialogEvent instanceof CopyCornersEvent) {
			// get the indexes of all roi managers with at least a roi added
			List<Integer> imageIndexes =  manager.getRoiManagers().stream()
					.filter(roiManager -> roiManager.getRoisAsArray().length != 0)
					.map(roiManager -> manager.getRoiManagers().indexOf(roiManager))
					.filter(index -> index != manager.getCurrentIndex())// remove the index of the current image, if present.
					.collect(Collectors.toList());

			Object[] options = imageIndexes.stream().map(imageIndex -> "Image " + (imageIndex + 1)).toArray();
			JComboBox optionList = new JComboBox(options);
			optionList.setSelectedIndex(0);

			int n = JOptionPane.showOptionDialog(new JFrame(), optionList,
					"Copy from", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
					null, new Object[] {"Copy", "Cancel"}, JOptionPane.YES_OPTION);

			if (n == JOptionPane.YES_OPTION) {
				RoiManager selectedManager = manager.getRoiManagers().get(imageIndexes.get(optionList.getSelectedIndex()));
				List<Point> roiPoints = Arrays.stream(selectedManager.getRoisAsArray()).map(roi -> new Point((int)roi.getRotationCenter().xpoints[0], (int)roi.getRotationCenter().ypoints[0]))
						.collect(Collectors.toList());
				roiPoints.stream().filter(roiCoords-> roiCoords.x < image.getWidth() && roiCoords.y < image.getHeight())
						.forEach(roiCoords ->this.onMainDialogEvent(new AddRoiEvent(roiCoords)));

				if(roiPoints.stream().anyMatch(roiCoords-> roiCoords.x > image.getWidth() || roiCoords.y > image.getHeight()))
					JOptionPane.showMessageDialog(null, ROI_NOT_ADDED_MESSAGE, "Warning", JOptionPane.WARNING_MESSAGE);

				this.image.setCopyCornersMode();
			}
		}

		if(dialogEvent instanceof RemoveImageEvent) {
			if(this.removeImageDialog != null && this.removeImageDialog.isVisible())
				return null;

			this.loadingDialog.showDialog();
			Utilities.setTimeout(() -> {
				this.removeImageDialog = new RemoveImageDialog(this.manager.getImageFiles(), this);
				this.removeImageDialog.setVisible(true);
				this.loadingDialog.hideDialog();
				this.loadingDialog.requestFocus();
			}, 20);
		}

		return null;
	}

	private void addToVirtualStack(ImagePlus img, VirtualStack virtualStack) {
		String path = IJ.getDir("temp") + img.getProcessor().hashCode()  + ".tiff";
		new FileSaver(img).saveAsTiff(path);
		virtualStack.addSlice(new File(path).getName());
		this.tempImages.add(path);
	}

	@Override
	public void onPreviewDialogEvent(IPreviewDialogEvent dialogEvent) {
		if(dialogEvent instanceof DS4H.PreviewDialog.event.ChangeImageEvent) {
			DS4H.PreviewDialog.event.ChangeImageEvent event = (DS4H.PreviewDialog.event.ChangeImageEvent)dialogEvent;
			new Thread(() -> {
				WindowManager.setCurrentWindow(image.getWindow());
				BufferedImage image = manager.get(event.getIndex());
				previewDialog.changeImage(image, "Preview Image " + (event.getIndex()+1) + "/" + manager.getNImages());
				this.loadingDialog.hideDialog();
				System.gc();
			}).start();
			this.loadingDialog.showDialog();
		}

		if(dialogEvent instanceof CloseDialogEvent) {
			mainDialog.setPreviewWindowCheckBox(false);
		}
	}

	@Override
	public void onAlignDialogEventListener(IAlignDialogEvent dialogEvent) {

		if(dialogEvent instanceof SaveEvent) {
			SaveDialog saveDialog = new SaveDialog("Save as", "aligned", ".tiff");
			if (saveDialog.getFileName()==null) {
				loadingDialog.hideDialog();
				return;
			}
			String path = saveDialog.getDirectory()+saveDialog.getFileName();
			loadingDialog.showDialog();
			new FileSaver(alignDialog.getImagePlus()).saveAsTiff(path);
			loadingDialog.hideDialog();
			JOptionPane.showMessageDialog(null, IMAGE_SAVED_MESSAGE, "Save complete", JOptionPane.INFORMATION_MESSAGE);
			this.alignedImageSaved = true;
		}

		if(dialogEvent instanceof ReuseImageEvent) {
			this.disposeAll();
			this.initialize(tempImages.get(tempImages.size()-1));
		}

		if(dialogEvent instanceof DS4H.AlignDialog.event.ExitEvent) {
			if(!alignedImageSaved) {
				String[] buttons = { "Yes", "No"};
				int answer = JOptionPane.showOptionDialog(null, ALIGNED_IMAGE_NOT_SAVED_MESSAGE, "Careful now",
						JOptionPane.WARNING_MESSAGE, 0, null, buttons, buttons[1]);
				if(answer == 1)
					return;
			}
			alignDialog.setVisible(false);
			alignDialog.dispose();
		}
	}

	@Override
	public void onRemoveDialogEvent(IRemoveDialogEvent removeEvent) {
		if(removeEvent instanceof DS4H.RemoveDialog.event.ExitEvent) {
			removeImageDialog.setVisible(false);
			removeImageDialog.dispose();
		}

		if(removeEvent instanceof DS4H.RemoveDialog.event.RemoveImageEvent) {
			int imageFileIndex = ((DS4H.RemoveDialog.event.RemoveImageEvent)removeEvent).getImageFileIndex();

			// only a image is available: if user remove this image we need to ask him to choose another one!
			if(this.manager.getImageFiles().size() == 1) {
				String[] buttons = { "Yes", "No"};
				int answer = JOptionPane.showOptionDialog(null, DELETE_ALL_IMAGES, "Careful now",
						JOptionPane.WARNING_MESSAGE, 0, null, buttons, buttons[1]);

				if(answer == 0) {
					String pathFile = promptForFile();
					if (pathFile.equals("nullnull")){

						disposeAll();
						System.exit(0);
						return;
					}
					this.disposeAll();
					this.initialize(pathFile);
				}
			}
			else {

				// remove the image selected
				this.removeImageDialog.removeImageFile(imageFileIndex);
				this.manager.removeImageFile(imageFileIndex);
				image = manager.get(manager.getCurrentIndex());
				mainDialog.changeImage(image);
				mainDialog.setPrevImageButtonEnabled(manager.hasPrevious());
				mainDialog.setNextImageButtonEnabled(manager.hasNext());
				mainDialog.setTitle(MessageFormat.format("Editor Image {0}/{1}", manager.getCurrentIndex() + 1, manager.getNImages()));
				this.refreshRoiGUI();
				System.gc();
			}
		}
	}

	/**
	 * Refresh all the Roi-based guis in the MainDialog
	 */
	private void refreshRoiGUI() {

		mainDialog.drawRois(image.getManager());
		if(previewDialog != null && previewDialog.isVisible())
			previewDialog.drawRois();

		// Get the number of rois added in each image. If they are all the same (and at least one is added), we can enable the "align" functionality
		List<Integer> roisNumber = manager.getRoiManagers().stream().map(roiManager -> roiManager.getRoisAsArray().length).collect(Collectors.toList());
		boolean alignButtonEnabled = roisNumber.get(0) >= LeastSquareImageTransformation.MINIMUM_ROI_NUMBER && manager.getNImages() > 1 && roisNumber.stream().distinct().count() == 1;
		// check if: the number of images is more than 1, ALL the images has the same number of rois added and the ROI numbers are more than 3
		mainDialog.setAlignButtonEnabled(alignButtonEnabled);

		boolean copyCornersEnabled = manager.getRoiManagers().stream()
				.filter(roiManager -> roiManager.getRoisAsArray().length != 0)
				.map(roiManager -> manager.getRoiManagers().indexOf(roiManager))
				.filter(index -> index != manager.getCurrentIndex()).count() != 0;
		mainDialog.setCopyCornersEnabled(copyCornersEnabled);
	}
	/**
	 * Initialize the plugin opening the file specified in the mandatory param
	 */
	public void initialize(String pathFile) {

		Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
			this.loadingDialog.hideDialog();
			if(e instanceof  OutOfMemoryError){
				this.loadingDialog.hideDialog();
				JOptionPane.showMessageDialog(null, INSUFFICIENT_MEMORY_MESSAGE, "Error: insufficient memory", JOptionPane.ERROR_MESSAGE);
				System.exit(0);
			}
		});
		this.aboutDialog = new AboutDialog();
		this.loadingDialog = new LoadingDialog();
		this.loadingDialog.showDialog();
		alignedImageSaved = false;
		boolean complete = false;
		try {

			try {
				long memory = ImageFile.estimateMemoryUsage(pathFile);
				TotalMemory += memory;
				if(TotalMemory >= Runtime.getRuntime().maxMemory()) {
					JOptionPane.showMessageDialog(null, INSUFFICIENT_MEMORY_MESSAGE, "Error: insufficient memory", JOptionPane.ERROR_MESSAGE);
					this.run();
				}
			}
			catch(UnknownFormatException e){
				loadingDialog.hideDialog();
				JOptionPane.showMessageDialog(null, UNKNOWN_FORMAT_MESSAGE, "Error: unknown format", JOptionPane.ERROR_MESSAGE);
			}
			manager = new ImagesManager(pathFile);
			image = manager.next();
			mainDialog = new MainDialog(image, this);
			mainDialog.setPrevImageButtonEnabled(manager.hasPrevious());
			mainDialog.setNextImageButtonEnabled(manager.hasNext());
			mainDialog.setTitle(MessageFormat.format("Editor Image {0}/{1}", manager.getCurrentIndex() + 1, manager.getNImages()));

			mainDialog.pack();
			mainDialog.setVisible(true);

			this.loadingDialog.hideDialog();
			if(image.isReduced())
				JOptionPane.showMessageDialog(null, IMAGES_SCALED_MESSAGE, "Info", JOptionPane.INFORMATION_MESSAGE);
			if(manager.getNImages() == 1)
				JOptionPane.showMessageDialog(null, SINGLE_IMAGE_MESSAGE, "Warning", JOptionPane.WARNING_MESSAGE);
			complete = true;
		}
		catch (ImagesManager.ImageOversizeException e) {
			JOptionPane.showMessageDialog(null, IMAGES_OVERSIZE_MESSAGE);
		}
		catch(UnknownFormatException e){
			JOptionPane.showMessageDialog(null, UNKNOWN_FORMAT_MESSAGE, "Error: unknown format", JOptionPane.ERROR_MESSAGE);
		}
		catch(loci.common.enumeration.EnumException e) {
			JOptionPane.showMessageDialog(null, UNKNOWN_FORMAT_MESSAGE, "Error: unknown format", JOptionPane.ERROR_MESSAGE);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			this.loadingDialog.hideDialog();
			if(!complete) {
				this.run();
			}

		}
	}

	private String promptForFile() {
		OpenDialog od = new OpenDialog("Select an image");
		String dir = od.getDirectory();
		String name = od.getFileName();
		return (dir + name);
	}

	/**
	 * Dispose all the opened workload objects.
	 */
	private void disposeAll() {
		this.mainDialog.dispose();
		this.loadingDialog.hideDialog();
		this.loadingDialog.dispose();
		if(this.previewDialog != null)
			this.previewDialog.dispose();
		if(this.alignDialog != null)
			this.alignDialog.dispose();
		if(this.removeImageDialog != null)
			this.removeImageDialog.dispose();
		this.manager.dispose();
		System.gc();
		TotalMemory = 0;
	}

}

