package com.memora.application.photo;

import java.util.List;

public record PhotoPage(List<PhotoView> items, String nextCursor) {
}
