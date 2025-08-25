# Native Audio Engine Demo

This is a comprehensive demo application showcasing all the features of the Capacitor Audio Engine plugin using modern Angular and Ionic components.

## Features Demonstrated

### 🎙️ Recording Tab
- **Permission Management**: Request and check microphone permissions
- **Audio Recording**: Start, pause, resume, and stop recording
- **Real-time Status**: See recording status and duration in real-time
- **Recording Options**: Configure sample rate, channels, bitrate, and maximum duration
- **File Management**: View all recorded files with metadata

### 🎵 Playback Tab
- **Audio Playback**: Play, pause, resume, and stop audio files
- **Playback Controls**:
  - Speed control (0.5x to 2.0x)
  - Volume control (0% to 100%)
  - Loop mode toggle
- **Progress Tracking**: See real-time playback progress and duration
- **Multi-file Support**: Resume any audio file with custom settings

### 🎙️ Microphones Tab
- **Microphone Detection**: List all available microphones (internal/external)
- **Status Monitoring**: Check if microphone is busy or available
- **Hot Switching**: Switch between microphones during recording
- **Device Information**: View microphone type, connection status, and details

### 📊 Audio Info Tab
- **File Analysis**: Get detailed metadata from audio files
- **File Properties**: View duration, size, sample rate, channels, bitrate
- **Format Information**: See MIME type and file paths
- **Creation Details**: View when files were created

## Additional Features

### 🛠️ Audio Processing
- **Audio Trimming**: Cut audio files to specific start and end times
- **File Organization**: Automatic file management with timestamps
- **Format Consistency**: All recordings use AAC format in M4A container

### 📱 Platform Support
- **iOS**: Full feature support with AVAudioRecorder/AVAudioPlayer
- **Android**: Full feature support with MediaRecorder/MediaPlayer
- **Cross-platform UI**: Responsive Ionic components that work on all devices

## Technical Implementation

### Modern Angular Features
- **Signals**: Reactive state management with Angular signals
- **Standalone Components**: No NgModules, pure standalone architecture
- **Change Detection**: OnPush strategy for optimal performance
- **Control Flow**: Modern `@if`, `@for` template syntax
- **Dependency Injection**: Modern `inject()` function usage

### Ionic Components Used
- **IonSegment**: Tab-based navigation between features
- **IonCard**: Feature organization and visual grouping
- **IonButton**: Interactive controls with proper states
- **IonChip**: Status indicators with color coding
- **IonProgressBar**: Recording and playback progress
- **IonRange**: Volume and speed controls
- **IonList**: File and microphone listings
- **IonAlert**: User input for audio trimming
- **IonToast**: User feedback and notifications

### Audio Engine Integration
- **Event Listeners**: Real-time monitoring of recording and playback events
- **Error Handling**: Comprehensive error catching and user feedback
- **Permission Flow**: Seamless permission request and status checking
- **File Management**: Automatic organization of recorded files

## Running the Demo

1. **Install Dependencies**:
   ```bash
   npm install
   ```

2. **Run in Browser** (limited functionality):
   ```bash
   npm start
   ```

3. **Run on iOS**:
   ```bash
   npm run ios
   ```

4. **Run on Android**:
   ```bash
   npm run android
   ```

## Code Structure

- `features-demo.component.ts`: Main component with all audio engine integration
- `features-demo.component.html`: Ionic template with tab-based UI
- `features-demo.component.scss`: Styling with responsive design and animations

## Best Practices Demonstrated

1. **Signal-based State**: All component state managed with signals
2. **Type Safety**: Full TypeScript integration with plugin types
3. **Error Boundaries**: Comprehensive error handling
4. **User Experience**: Loading states, progress indicators, and feedback
5. **Responsive Design**: Mobile-first design that works on all screen sizes
6. **Accessibility**: Proper semantic markup and ARIA labels
7. **Performance**: OnPush change detection and lazy loading

This demo serves as both a testing ground for the plugin and a reference implementation for developers integrating the Capacitor Audio Engine into their applications.
