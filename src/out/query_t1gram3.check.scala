/*****************************************
Emitting Generated Code
*******************************************/
class Snippet extends ((java.lang.String)=>(Unit)) {
  def apply(x0:java.lang.String): Unit = {
    val x1 = println("Phrase,Year,MatchCount,VolumeCount,VolumeCount1")
    val x2 = new scala.lms.tutorial.Scanner(x0)
    val x21 = while ({val x3 = x2.hasNext
      x3}) {
      val x5 = x2.next('\t')
      val x6 = x2.next('\t')
      val x7 = x2.next('\t')
      val x8 = x2.next('\n')
      val x9 = new scala.lms.tutorial.Scanner("src/data/words.csv")
      val x10 = x9.next(',')
      val x11 = x9.next('\n')
      val x18 = while ({val x12 = x9.hasNext
        x12}) {
        val x14 = x9.next(',')
        val x15 = x9.next('\n')
        val x16 = printf("%s,%s,%s,%s,%s\n",x5,x6,x7,x8,x15)
        x16
      }
      val x19 = x9.close
      x19
    }
    val x22 = x2.close
    ()
  }
}
/*****************************************
End of Generated Code
*******************************************/
