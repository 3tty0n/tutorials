/*****************************************
Emitting C Generated Code
*******************************************/
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
void Snippet(string  x0) {
  printf("%s\n",string("Name,Value,Flag,Name").c_str());
  (void*)* x2 = ((void*)*)malloc(65536 * sizeof((void*)));
  (void*)* x3 = ((void*)*)malloc(65536 * sizeof((void*)));
  (void*)* x4 = ((void*)*)malloc(65536 * sizeof((void*)));
  int32_t x5 = 0;
  int32_t x6 = 0;
  int32_t* x7 = (int32_t*)malloc(65536 * sizeof(int32_t));
  (void*)* x8 = ((void*)*)malloc(256 * sizeof((void*)));
  int32_t x9 = 0;
  int32_t* x10 = (int32_t*)malloc(256 * sizeof(int32_t));
  for(int x12=0; x12 < 256; x12++) {
    x10->update(x12,0);
  }
  Scanner *x16 = new scala.lms.tutorial.Scanner(string("src/data/t.csv"));
  string x17 = x16.next;
  string x18 = x16.next;
  string x19 = x16.next;
  for (;;) {
    bool x20 = x16.hasNext;
    if (!x20) break;
    string x22 = x16.next;
    string x23 = x16.next;
    string x24 = x16.next;
    int32_t x25 = x6;
    x2->update(x25,x22);
    x3->update(x25,x23);
    x4->update(x25,x24);
    x6 += 1;
    //#hash_lookup
    // generated code for hash lookup
    int64_t x30 = x22.##;
    int32_t x31 = (int32_t)x30;
    int32_t x32 = x31 & 255;
    int32_t x33 = x32;
    for (;;) {
      int32_t x34 = x33;
      int32_t x35 = x10->apply(x34);
      (void*) *x37 = x8->apply(x34);
      bool x38 = x37 == x22;
      bool x36 = x35 != 0;
      bool x39 = true && x38;
      bool x40 = !x39;
      bool x41 = x36 && x40;
      if (!x41) break;
      int32_t x43 = x33;
      int32_t x44 = x43 + 1;
      int32_t x45 = x44 & 255;
      x33 = x45;
    }
    int32_t x49 = x33;
    x8->update(x49,x22);
    int32_t x52 = x49;
    //#hash_lookup
    int32_t x53 = x10->apply(x52);
    int32_t x54 = x52 * 256;
    int32_t x55 = x54 + x53;
    x7->update(x55,x25);
    int32_t x57 = x53 + 1;
    x10->update(x52,x57);
  }
  Scanner *x61 = new scala.lms.tutorial.Scanner(string("src/data/t.csv"));
  string x62 = x61.next;
  string x63 = x61.next;
  string x64 = x61.next;
  for (;;) {
    bool x65 = x61.hasNext;
    if (!x65) break;
    string x67 = x61.next;
    string x68 = x61.next;
    string x69 = x61.next;
    //#hash_lookup
    // generated code for hash lookup
    int64_t x70 = x67.##;
    int32_t x71 = (int32_t)x70;
    int32_t x72 = x71 & 255;
    int32_t x73 = x72;
    for (;;) {
      int32_t x74 = x73;
      int32_t x75 = x10->apply(x74);
      (void*) *x77 = x8->apply(x74);
      bool x78 = x77 == x67;
      bool x76 = x75 != 0;
      bool x79 = true && x78;
      bool x80 = !x79;
      bool x81 = x76 && x80;
      if (!x81) break;
      int32_t x83 = x73;
      int32_t x84 = x83 + 1;
      int32_t x85 = x84 & 255;
      x73 = x85;
    }
    int32_t x89 = x73;
    int32_t x91 = x89;
    //#hash_lookup
    int32_t x92 = x10->apply(x91);
    int32_t x93 = x91 * 256;
    int32_t x94 = x93 + x92;
    for(int x96=x93; x96 < x94; x96++) {
      int32_t x97 = x7->apply(x96);
      (void*) *x98 = x2->apply(x97);
      (void*) *x99 = x3->apply(x97);
      (void*) *x100 = x4->apply(x97);
      x98+string(",")// strcat
      x99+string(",")// strcat
      x100+string(",")// strcat
      x103+x67// strcat
      x102+x104// strcat
      x101+x105// strcat
      printf("%s\n",x106.c_str());
    }
  }
}
/*****************************************
End of C Generated Code
*******************************************/
