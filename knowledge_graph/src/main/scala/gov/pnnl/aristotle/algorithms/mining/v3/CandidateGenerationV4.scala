/**
 *
 * @author puro755
 * @dAug 22, 2016
 * @knowledge_graph
 */
package gov.pnnl.aristotle.algorithms.mining.v3

import java.io.Serializable
import org.apache.spark.graphx.Graph
import gov.pnnl.aristotle.algorithms.mining.datamodel.KGNodeV4
import gov.pnnl.aristotle.algorithms.mining.datamodel.KGEdgeInt
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext
import java.io.PrintWriter
import org.apache.spark.graphx.VertexRDD
import gov.pnnl.aristotle.algorithms.mining.datamodel.PatternInstance
import scalaz.Scalaz._
import org.apache.spark.graphx.Edge
import gov.pnnl.aristotle.algorithms.mining.GraphProfiling
import gov.pnnl.aristotle.algorithms.mining.datamodel.PatternInstance
import gov.pnnl.aristotle.algorithms.mining.datamodel.PatternInstanceNode
import org.apache.spark.rdd.RDD
import gov.pnnl.aristotle.algorithms.mining.datamodel.PatternInstanceNode

/**
 * @author puro755
 *
 */
class CandidateGenerationV4(val minSup: Int) extends Serializable {

  var TYPE: Int = 0
  var SUPPORT: Int = minSup
  var type_support: Int = 2
  var input_gpi: Graph[KGNodeV4, KGEdgeInt] = null
  val batch_id_map : Map[Int,(Long,Long)] = Map.empty
  println("**************CREATING GRAPH************")
  
  
    def init(sc : SparkContext, graph: Graph[Int, KGEdgeInt], writerSG: PrintWriter, basetype: Int,
    type_support: Int):  Graph[PatternInstanceNode, Int] = {

    /*
     * Get all the rdf:type dst node information on the source node
     */
    this.TYPE = basetype
    this.type_support = type_support
    println("***************support is " + SUPPORT)

    // Now we have the type information collected in the original graph
    val typedAugmentedGraph: Graph[(Int, Map[Int, Int]), KGEdgeInt] = getTypedGraph(graph, writerSG)

    /*
     * Create RDD where Every vertex has all the 1 edge patterns it belongs to
     * Ex: Sumit: (person worksAt organizaion) , (person friendsWith person)
     * 
     * Read method comments
     * ....
     * ....
     * 
     */
    val oneEdgePatternOnVertexRDD: VertexRDD[Array[(List[Int], PatternInstance)]] = getOneEdgePatternsRDD(typedAugmentedGraph)    

    val gipVertices = getGIPVertices(typedAugmentedGraph)
    
    val gipEdge = getGIPEdges(oneEdgePatternOnVertexRDD)
    
    return  Graph(gipVertices,gipEdge)
  }
  
    def getTypedGraph(graph: Graph[Int, KGEdgeInt],
    writerSG: PrintWriter): Graph[(Int, Map[Int, Int]), KGEdgeInt] =
    {
      val typedVertexRDD: VertexRDD[Map[Int, Int]] =
        GraphProfiling.getTypedVertexRDD_Temporal(graph,
          writerSG, type_support, this.TYPE.toInt)
      // Now we have the type information collected in the original graph
      val typedAugmentedGraph: Graph[(Int, Map[Int, Int]), KGEdgeInt] = GraphProfiling.getTypedAugmentedGraph_Temporal(graph,
        writerSG, typedVertexRDD)
      return typedAugmentedGraph
    }
 
 def getGIPEdges(oneEdgePatternOnVertexRDD: VertexRDD[Array[(List[Int], PatternInstance)]]) :
 RDD[Edge[Int]] =
 {
     /*
     * Create Edges of the GIP
     * 
     * getGIPVertices has similar code to find Vertices, but for Vertices, we need to carry much more data.
     * Also if we try to store fat data on every vertex, it may become bottleneck
     * 
     */
      val gipEdges = oneEdgePatternOnVertexRDD.flatMap(vertex => {
        var all_gip_vertices: scala.collection.mutable.ListBuffer[List[Int]] = scala.collection.mutable.ListBuffer.empty
        vertex._2.map(a_pattern => all_gip_vertices +=
          (a_pattern._1 ++ List(a_pattern._2.get_instacne.head._1, a_pattern._2.get_instacne.head._2)))
        // 'head' is used here because at this point of time it is only an one-edge instance
        val all_gip_vertices_list = all_gip_vertices.toList
        // make a list to get an order because we need to cross join them
        // in next step.
        var i = 0 ; var j = 0
        var local_edges: scala.collection.mutable.Set[Edge[Int]] = scala.collection.mutable.Set.empty
        for (i <- 0 to all_gip_vertices_list.length - 1) {
          for (j <- i to all_gip_vertices_list.length - 1) {
            if (all_gip_vertices_list(i) != all_gip_vertices_list(j))
              local_edges += Edge(all_gip_vertices_list(i).hashCode, all_gip_vertices_list(j).hashCode, 1)
          }
        }
        local_edges
      })
      return gipEdges
    }
    
 def getGIPVertices(typedAugmentedGraph: Graph[(Int, Map[Int, Int]), KGEdgeInt] ) :
 RDD[(Long,PatternInstanceNode)] =
 {
       /*
     * Create GIP from this graph
     * 
     * Every node has following structure:
     * (Long,(List[Int],Set[PatternInstance],Long)
     * Long: VertexId
     * List[Int]: pattern key
     * Set[PatternInstance]: pattern instances around the local edge 
     * Long : timestamp of that pattern edge
     */
    val allGIPNodes : RDD[(Long,PatternInstanceNode)]= 
      typedAugmentedGraph.triplets.filter(triple=>triple.attr.getlabel != TYPE).flatMap(triple => {

      //Local Execution on a triple edge; but needs source and destination
      val source_node = triple.srcAttr
      val destination_node = triple.dstAttr
      val time_stamp = triple.attr.getdatetime
      val all_src_types = source_node._2.keys
      val all_dst_types = destination_node._2.keys

      var all_local_gip_vertices: scala.collection.mutable.Set[(Long, PatternInstanceNode)] = scala.collection.mutable.Set.empty
      
      for (src_type <- all_src_types)
        for (dst_type <- all_dst_types) {
          val gip_v_key = List(src_type , triple.attr.getlabel, dst_type, triple.srcAttr._1, triple.dstAttr._1).hashCode
          val gip_v_label = List(src_type , triple.attr.getlabel, dst_type, triple.srcAttr._1, triple.dstAttr._1)
          val gip_v_instance = new PatternInstance(Set((triple.srcAttr._1, triple.dstAttr._1)))
          all_local_gip_vertices += ((gip_v_key, new PatternInstanceNode(gip_v_label, gip_v_instance, time_stamp)))
        }
      all_local_gip_vertices
    })
    return allGIPNodes
 }
    
 def getOneEdgePatternsRDD(typedAugmentedGraph: Graph[(Int,  
    Map[Int, Int]), KGEdgeInt]): VertexRDD[Array[(List[Int],PatternInstance)]] =
    {
      return typedAugmentedGraph.aggregateMessages[Array[(List[Int], PatternInstance)]](
        edge => {
          if (edge.attr.getlabel != TYPE) {
            // Extra info for pattern
            if ((edge.srcAttr._2.size > 0) &&
              (edge.dstAttr._2.size > 0)) {
              val dstnodetype = edge.dstAttr._2.keys
              val srcnodetype = edge.srcAttr._2.keys
              srcnodetype.foreach(s => {
                dstnodetype.foreach(d => {
                  var pattern_instance: scala.collection.immutable.Set[(Int, Int)] = Set((edge.srcAttr._1,edge.dstAttr._1))
                  edge.sendToSrc(Array(List(s, edge.attr.getlabel,
                    d)
                    -> new PatternInstance(pattern_instance)))
                  edge.sendToDst(Array(List(s, edge.attr.getlabel,
                    d)
                    -> new PatternInstance(pattern_instance)))
                })
              })
            }
          }
        },
        (pattern1NodeN, pattern2NodeN) => {
          reducePatternsOnNodeV2(pattern1NodeN, pattern2NodeN)
        })
    }
  def reducePatternsOnNodeV2(a: Array[(List[Int], PatternInstance)], 
      b: Array[(List[Int], PatternInstance)]): 
	  Array[(List[Int], PatternInstance)] =
    {
      
    return a ++ b
    //This may leads to a large array on node with same pattern. For now, it can be used to 
    // create GIP. 
    //Option 2: call getCondensedVRDD
    
    }
  
  
  
  def maintainWindow(input_gpi: Graph[(Long,PatternInstanceNode), Int], cutoff_time : Long) 
  : Graph[(Long,PatternInstanceNode), Int] =
	{
		return input_gpi.subgraph(vpred = (vid,attr) => {
		  attr._2.timestamp > cutoff_time
		})
	}
  
  def computeMinImageSupport(input_gpi : Graph[(Long,PatternInstanceNode), Int])
	  :RDD[(List[Int],Int)] =
  {

      /*
     * A flat RDD like:
     * (P1,person,sp)
     * (P1,person,sc)
     * (P1,org,pnnl)
     * (P1,org,pnnl)
     */
      val sub_pattern_key_rdd = input_gpi.vertices.flatMap(vertext => {
        vertext._2._2.pattern_instance_map.map(pattern_instance_pair => {
          ((vertext._2._2.pattern, pattern_instance_pair._1, pattern_instance_pair._2))
        })
      }).distinct
      //.reduceByKey((sub_pattern_instance_count1, sub_pattern_instance_count2) => sub_pattern_instance_count1 + sub_pattern_instance_count2)

      
      val mis_rdd = sub_pattern_key_rdd.map(key=>{
        ((key._1, key._2),1)
         /*
         * ((P1,person) , 1) from (P1,person,sp)
         * ((P1,person) , 1) from (P1,person,sc)
         * ((P1,org) , 1) from (P1,org,pnnl)
         * 
         */

      }).reduceByKey((unique_instance1_count,unique_instance2_count) 
          => unique_instance1_count + unique_instance2_count)
      
     /*
     * Input is 'mis_rdd' which is a Cumulative RDD like:
     * P1:person, 2
     * P1:org, 1
     * 
     * Output is patternSup which gets minimum of all P1:x 
     * so return (P1, 1)
     */
      val patternSup: RDD[(List[Int], Int)] = mis_rdd.map(sup_pattern_key => {
        //Emitt (P1, 2) and (P1 1)
       (sup_pattern_key._1._1,sup_pattern_key._2)
      }).reduceByKey((full_pattern_instace_count1, full_pattern_instace_count2) => {
        /*
       * Not using Math lib to because it loads entire dir for min function.
       * Also seen it failing in cluster mode.
       */
        if (full_pattern_instace_count1 < full_pattern_instace_count2)
          full_pattern_instace_count1
        else
          full_pattern_instace_count2
      })

      return patternSup
    }
  
}