package com.piggie.tv.data.reader

enum class ReaderFormat(val extension: String, val mimeType: String) {
    CBZ(".cbz", "application/x-cbz"),
    CBR(".cbr", "application/x-cbr"),
    PDF(".pdf", "application/pdf"),
    EPUB(".epub", "application/epub+zip"),
    IMAGE(".jpg", "image/jpeg"),
    JELLYFIN_PAGES("", ""),
    UNKNOWN("", "")
}
