package com.capacitor.audioengine;

import org.json.JSONException;
import org.json.JSONObject;

public class AudioTrack {
  private final String id;
  private final String url;
  private final String title;
  private final String artist;
  private final String artworkUrl;

  public AudioTrack(String id, String url, String title, String artist, String artworkUrl) {
    this.id = id;
    this.url = url;
    this.title = title != null ? title : "Unknown Title";
    this.artist = artist != null ? artist : "Unknown Artist";
    this.artworkUrl = artworkUrl;
  }

  // Getter methods
  public String getId() {
    return id;
  }

  public String getUrl() {
    return url;
  }

  public String getTitle() {
    return title;
  }

  public String getArtist() {
    return artist;
  }

  public String getArtworkUrl() {
    return artworkUrl;
  }

  public JSONObject toJSON() throws JSONException {
    JSONObject json = new JSONObject();
    json.put("id", id);
    json.put("url", url);
    json.put("title", title);
    json.put("artist", artist);
    json.put("artworkUrl", artworkUrl != null ? artworkUrl : "");
    return json;
  }
}
