#pragma once
#include <stddef.h>
#include <stdint.h>

void *memset(void *dest, int c, unsigned int n);
void *memcpy(void *dest, const void *src, unsigned int n);
void *memmove(void *dest, const void *src, unsigned int n);
size_t strlen(const char *s);

void *malloc(size_t n);
void free(void *p);
void *calloc(size_t nmemb, size_t size);